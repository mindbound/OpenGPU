package io.github.mindbound.opengpu.ocelot

import java.nio.charset.StandardCharsets
import java.util.zip.Adler32

import scala.jdk.CollectionConverters._

import io.github.mindbound.opengpu.core.cmd.Formats
import io.github.mindbound.opengpu.core.device.DeviceCaps
import io.github.mindbound.opengpu.core.fixtures.device.M0Callbacks
import io.github.mindbound.opengpu.core.render.RenderHost
import org.junit.jupiter.api.Assertions.{assertEquals, assertNull, assertTrue, fail}
import org.junit.jupiter.params.provider.Arguments

/**
  * The self-test's pixels line: its frame id, each chunk's printed Adler-32, the verdict after them, and the
  * `"dropped"` statuses the program noted after the verdict, which are not final (28:112; S13 review spec-1).
  */
final case class SelfTestPixels(frame: Long, digests: Seq[Long], verdict: String, dropped: Int)

/**
  * One finished run of the program: its printed lines, as `shown` for a failure message too, its pixels line, and
  * Java's Adler-32 of each chunk of the readback the host delivered for the frame that line names.
  */
final case class SelfTestRun(lines: Seq[String], shown: String, pixels: SelfTestPixels, computed: Seq[Long])

/**
  * The Java side of the self-test program, `opengpu_selftest.lua` (S13 decisions D1; plan P4, P6; amendments F3, F6,
  * I2): its cases, its rig, the lines it prints and the checks SelfTestOracleTest and SelfTestGlTest share. The
  * program runs unchanged through selftest_run.lua, on one card and no display, so it binds nothing and its sweep
  * reports `bind` absent (amendments F7, I2).
  *
  * The expected digests are the program's EXPECT block, which this side holds to the tier-E oracle on every build:
  * Java computes `java.util.zip.Adler32` of each readPixels chunk of the readback the host delivered for the program's
  * frame, chunked as the program chunks it, from `DeviceCaps.forTier`, and asserts that the values equal both the
  * block's and the ones the program printed (P4.4). On the oracle leg that readback is the tier-E oracle's rendering of
  * exactly the records the program submitted, with font 0's real table (P4.5); on the GL leg it is the FBO's, which the
  * tee's shadow oracle holds to the same rendering. No image is kept, so the block is not a golden: a deliberate change
  * to the frame fails here first, and the failure message gives the block to copy into the program. The block is keyed
  * by each surface's format and size, as the program looks it up (S13 review spec-4).
  */
object SelfTest {
  val Program: String = "opengpu_selftest.lua"

  /** The program's marker lines around its copy of 28 §9.2's builders (BuildersVerbatimTest, P6.4). */
  val BuildersBegin: String =
    "-- BEGIN 28 §9.2's reference record builders, verbatim (BuildersVerbatimTest.theSelfTestsBuildersAre28sBlock)"
  val BuildersEnd: String = "-- END 28 §9.2's reference record builders"

  /** The program's marker lines around its expected digests (P4.3); comment lines between them are not the block's. */
  val DigestsBegin: String = "-- BEGIN expected digests"
  val DigestsEnd: String = "-- END expected digests"

  /** The surfaces the block covers, by tier, in its order: T2's and T3's (28 §8.1). */
  val Tiers: Seq[Int] = Seq(2, 3)

  /** The program's result shapes, in its sweep line's order (P5.4). */
  val Shapes: Seq[String] =
    Seq("map", "{TRUE}", "{Integer}", "{Long}", "{byte[],Long}", "{null,String}", "argument-error", "signal")

  /**
    * The cases the program runs in the harness besides the readPixels chunks: getCaps, the sprite's createTexture,
    * updateTexture, submit and free, present, the frame's signal, and the 13 that need no frame. `bind` and its three
    * cases are the card's alone.
    */
  val HarnessCases: Int = 20

  /** (arch, tier) for every arch of the task, T2 and T3, for the oracle leg's `@MethodSource`. */
  def cases(): java.util.List[Arguments] =
    (for (arch <- Archs.running; tier <- Tiers) yield Arguments.of(arch, Int.box(tier))).asJava

  /** (arch, tier) for the task's full set, for the GL leg's: never empty but on a narrowed run (Archs.gating). */
  def fullCases(): java.util.List[Arguments] =
    (for (arch <- Archs.gating("the full set", Archs.full); tier <- Tiers) yield Arguments.of(arch, Int.box(tier)))
      .asJava

  /**
    * A rig whose script is selftest_run.lua, with `program`, the committed program unless a test passes another text,
    * as its blob "selftest" (P6.2).
    */
  def spec(name: String, arch: Arch, tier: Int, host: RenderHost, pump: () => Unit, closeHost: () => Unit = () => (),
      hostRenderer: Option[String] = None, program: String = Rig.resource(Program)): RigSpec =
    RigSpec(name, arch, "selftest_run.lua", tier = tier, host = host, pump = pump, closeHost = closeHost,
      hostRenderer = hostRenderer, blobs = Map("selftest" -> program.getBytes(StandardCharsets.UTF_8)))

  /** The program's text, line by line. */
  def programLines: Seq[String] = Rig.resource(Program).split("\r?\n", -1).toList

  /** The tier's surface format, as getCaps names it. */
  def format(tier: Int): String = Formats.name(DeviceCaps.forTier(tier).surface.format)

  /** The rows of one readPixels chunk on the tier's surface: `floor(maxReadbackBytes / (width * bpp))` (28:690). */
  def rows(tier: Int): Int = {
    val caps = DeviceCaps.forTier(tier)
    caps.maxReadbackBytes / (caps.surface.width * caps.surface.bytesPerPixel())
  }

  /** A surface in readPixels chunks of [[rows]] rows, as the program reads it. */
  def chunks(tier: Int, pixels: Array[Byte]): Seq[Array[Byte]] = {
    val caps = DeviceCaps.forTier(tier)
    assertEquals(caps.surface.byteSize(), pixels.length, s"T$tier: the surface's bytes")
    pixels.grouped(rows(tier) * caps.surface.width * caps.surface.bytesPerPixel()).toList
  }

  def adler32(bytes: Array[Byte]): Long = {
    val a = new Adler32
    a.update(bytes, 0, bytes.length)
    a.getValue
  }

  /** As the program prints a digest: "0x" and 8 upper-case hex digits. */
  def hex(v: Long): String = f"0x$v%08X"

  /** The non-comment lines between the program's digest markers. */
  def digestBlock(program: Seq[String]): Seq[String] = {
    val begin = program.indexWhere(_.startsWith(DigestsBegin))
    val end = program.indexWhere(_ == DigestsEnd, begin + 1)
    if (begin < 0 || end < 0) fail(s"$Program has no \"$DigestsBegin\" ... \"$DigestsEnd\" block")
    program.slice(begin + 1, end).filterNot(_.startsWith("--"))
  }

  /** The start of the block's entry for the tier's surface, keyed by its format and size, as the program reads it. */
  def digestPrefix(tier: Int): String = {
    val surface = DeviceCaps.forTier(tier).surface
    s"""EXPECT["${format(tier)} ${surface.width}x${surface.height}"] = """
  }

  /** The block's entry for the tier's surface, wrapped at the code's 120 characters, its later lines indented by 2. */
  def digestLines(tier: Int, digests: Seq[Long]): Seq[String] =
    if (digests.isEmpty) Seq(s"${digestPrefix(tier)}{ }")
    else {
      val words = digests.map(hex)
      (words.init.map(_ + ",") :+ s"${words.last} }").foldLeft(Vector(s"${digestPrefix(tier)}{")) { (lines, word) =>
        if (lines.last.length + 1 + word.length <= 120) lines.init :+ s"${lines.last} $word" else lines :+ s"  $word"
      }
    }

  /**
    * Where the tier's entry is in `lines`, from `from` on, and how many lines it has: its first line and the indented
    * ones after it; a failure when it has none.
    */
  private def entry(lines: Seq[String], tier: Int, from: Int = 0): (Int, Int) = {
    val at = lines.indexWhere(_.startsWith(digestPrefix(tier)), from)
    if (at < 0) fail(s"$Program's expected digests have no ${digestPrefix(tier)}{ ... }")
    (at, 1 + lines.drop(at + 1).takeWhile(_.startsWith("  ")).size)
  }

  /**
    * The block the program must have: `local EXPECT = {}`, then an entry per surface in [[Tiers]]' order, this tier's
    * from Java's digests and the others' as the program has them.
    */
  def wantedBlock(program: Seq[String], tier: Int, digests: Seq[Long]): Seq[String] = {
    val block = digestBlock(program)
    "local EXPECT = {}" +: Tiers.flatMap { t =>
      if (t == tier) digestLines(t, digests)
      else if (!block.exists(_.startsWith(digestPrefix(t)))) Seq.empty
      else entry(block, t) match { case (at, n) => block.slice(at, at + n) }
    }
  }

  /** The block's digests for the tier's surface, as the program has them. */
  def expected(program: Seq[String], tier: Int): Seq[Long] = {
    val block = digestBlock(program)
    val (at, n) = entry(block, tier)
    block.slice(at, at + n).mkString(" ").stripPrefix(digestPrefix(tier)).trim.stripPrefix("{").stripSuffix("}")
      .split(',').toList.map(_.trim).map(h => java.lang.Long.parseLong(h.stripPrefix("0x"), 16))
  }

  /**
    * The program's text with its block's entry for the tier's surface giving `digests` instead, for
    * SelfTestOracleTest's runs against wrong digests (S13 review tests-1).
    */
  def withDigests(tier: Int, digests: Seq[Long]): String = {
    val program = programLines
    val (at, n) = entry(program, tier, program.indexWhere(_.startsWith(DigestsBegin)))
    program.patch(at, digestLines(tier, digests), n).mkString("\n")
  }

  /** The lines the program printed through selftest_run.lua, in order. */
  def printed(rig: Rig): Seq[String] = rig.sink.all("print").map(_.str(0))

  /**
    * The run's printed lines and its pixels line, and Java's Adler-32 of each chunk of the readback the host delivered
    * for the frame that line names, which must equal the printed digests. A trailing `dropped <n>` on the pixels line
    * is the program's note of `"dropped"` statuses, not part of the verdict (28:112; S13 review spec-1).
    */
  def finished(rig: Rig, tier: Int, delivered: Long => Option[Array[Byte]]): SelfTestRun = {
    val lines = printed(rig)
    val shown = lines.mkString("\n  ", "\n  ", "")
    assertNull(rig.machine.lastError, "lastError")
    val line = lines.find(_.startsWith("card 1 pixels ")).getOrElse(fail(s"no pixels line:$shown"))
    val t = line.split(' ').toList
    if (t.lift(3) != Some("frame") || t.lift(5) != Some("chunks")) fail(s"the pixels line has no digests:$shown")
    val n = t(6).toInt
    val tail = t.drop(7 + n)
    val (verdict, dropped) = tail.takeRight(2) match {
      case List("dropped", k) if k.nonEmpty && k.forall(_.isDigit) => (tail.dropRight(2), k.toInt)
      case _ => (tail, 0)
    }
    val pixels = SelfTestPixels(t(4).toLong, t.slice(7, 7 + n).map(h => java.lang.Long.parseLong(h.drop(2), 16)),
      verdict.mkString(" "), dropped)
    val bytes = delivered(pixels.frame).getOrElse(fail(s"the host delivered no readback of frame ${pixels.frame}"))
    val computed = chunks(tier, bytes).map(adler32)
    assertEquals(computed.map(hex), pixels.digests.map(hex), "Java's Adler-32 of the delivered readback's chunks " +
      s"against the printed digests:$shown")
    SelfTestRun(lines, shown, pixels, computed)
  }

  /**
    * The checks both legs make on one finished run of T`tier` (P4.4, P6.3; amendments F6, I2, I5), given the pixels the
    * host delivered for a frame id, and returns the pixels line. In this order, so that a block to copy is shown even
    * when the program FAILed for want of it:
    *  - Java's Adler-32 of each chunk of the delivered readback of the printed frame equals the printed digest, and
    *    the program's EXPECT line for the surface; the failure message gives the block;
    *  - the verdict is `opengpu-selftest PASS`, the pixels line's verdict PASS, and the other lines are as
    *    [[theOtherLines]] says.
    */
  def check(rig: Rig, tier: Int, delivered: Long => Option[Array[Byte]], hostRenderer: Option[String])
      : SelfTestPixels = {
    val run = finished(rig, tier, delivered)
    val program = programLines
    val want = wantedBlock(program, tier, run.computed)
    assertEquals(want, digestBlock(program), s"$Program's expected digests for ${format(tier)} are not the tier-E " +
      s"oracle's; between its markers they must read:\n${want.mkString("\n")}\n")
    assertEquals(Some("opengpu-selftest PASS"), run.lines.lastOption, s"the verdict; the program printed:${run.shown}")
    theOtherLines(rig, tier, run, hostRenderer)
    assertEquals("PASS", run.pixels.verdict, s"the pixels line:${run.shown}")
    run.pixels
  }

  /**
    * The checks on a run whose program had wrong digests (S13 review tests-1): the printed digests are still Java's,
    * the pixels line's verdict is `verdict`, the program's own comparison's FAIL, the verdict line is
    * `opengpu-selftest FAIL 1`, and the other lines are as [[theOtherLines]] says, the sweep's PASS among them.
    */
  def checkFails(rig: Rig, tier: Int, delivered: Long => Option[Array[Byte]], verdict: Seq[Long] => String)
      : SelfTestRun = {
    val run = finished(rig, tier, delivered)
    assertEquals(verdict(run.computed), run.pixels.verdict, s"the pixels line:${run.shown}")
    assertEquals(Some("opengpu-selftest FAIL 1"), run.lines.lastOption, s"the verdict; it printed:${run.shown}")
    theOtherLines(rig, tier, run, None)
    run
  }

  /**
    * The lines besides the verdicts: exactly five, its header, with the number kind of the arch's name (P3.12), the
    * card's line, unbound, with its tier, format, size, renderer and hostRenderer, the pixels line third, and the sweep
    * line, exactly PASS, [[HarnessCases]] plus the chunks, M0Callbacks' names in their order with the card-only `bind`
    * absent, and all eight shapes (P5.4); and `"done"` was recorded for the frame.
    */
  private def theOtherLines(rig: Rig, tier: Int, run: SelfTestRun, hostRenderer: Option[String]): Unit = {
    val SelfTestRun(lines, shown, pixels, computed) = run
    assertEquals(5, lines.size, s"the program's lines:$shown")
    val arch = rig.spec.arch
    val kind = if (arch.integers) "integer" else "integral"
    val header = s"opengpu-selftest ${arch.name} (${arch.version}), numbers $kind, 1 card(s), free memory "
    assertTrue(lines.head.startsWith(header), s"the header, not '$header...':$shown")
    val surface = DeviceCaps.forTier(tier).surface
    assertEquals(s"card 1 ${rig.gpu.node.address.take(8)} T$tier ${format(tier)} ${surface.width}x${surface.height} " +
      s"display unbound renderer host-gl hostRenderer ${hostRenderer.getOrElse("-")}", lines(1),
      s"the card's line:$shown")
    assertTrue(lines(2).startsWith("card 1 pixels "), s"the pixels line's place:$shown")
    val callbacks = M0Callbacks.all().asScala.map(s => if (s.cardOnly) s"${s.name}:absent" else s.name)
    assertEquals(s"card 1 sweep PASS ${HarnessCases + computed.size} cases; callbacks ${callbacks.mkString(" ")}; " +
      s"shapes ${Shapes.mkString(" ")}", lines(3), s"the sweep line:$shown")
    assertTrue(rig.gpu.statuses.events().contains(s"done ${pixels.frame}"),
      s"no done for frame ${pixels.frame}: ${rig.gpu.statuses.events()}")
  }
}
