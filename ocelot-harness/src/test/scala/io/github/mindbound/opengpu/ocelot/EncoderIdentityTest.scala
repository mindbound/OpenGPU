package io.github.mindbound.opengpu.ocelot

import io.github.mindbound.opengpu.core.fixtures.TestEncoder
import org.junit.jupiter.api.Assertions.{assertArrayEquals, assertEquals, assertTrue, fail}
import org.junit.jupiter.api.{Tag, Timeout}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
  * "Lua `char` bytes == Java bytes" (28:907; S10 plan P14.3; decisions D4) through real OC: 28 §9.2's builders, which
  * ogpu_builders.lua holds verbatim (BuildersVerbatimTest), against `:core`'s TestEncoder on [[EncoderCorpus]], sent as
  * Lua-source batches of 100 through `cases(i)`; 28 §9.4's worked frame, built as 28 writes it, against 28:833-838's
  * 64 bytes and 28:842's T3 operands; and on 5.3 and 5.4 the `string.pack` path, which must give the `char` path's
  * bytes and TestEncoder's (28:813). It runs on the task's full set: 5.3 and 5.2 on the pack leg, 5.4 on the instance
  * leg, and the char path on LuaJ for correctness only (S10 decisions D5), which the pack-path tag leaves out of that
  * leg. A mismatch names the batch, the record and the first differing offset, with bounded hex on both sides.
  */
@Tag("ocelot-pack")
@Tag("ocelot-instance")
@Tag("ocelot-luaj")
class EncoderIdentityTest {
  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#fullSet"))
  @Timeout(120)
  def theCharPathGivesTestEncodersBytes(arch: Arch): Unit = corpus(arch, char = true, pack = false)

  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#packPath"))
  @Tag("pack-path")
  @Timeout(120)
  def thePackPathGivesTheCharPathsBytes(arch: Arch): Unit = corpus(arch, char = true, pack = true)

  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#fullSet"))
  @Timeout(120)
  def theWorkedFrameIs28sOnT2(arch: Arch): Unit = workedFrame(arch, 2, EncoderIdentityTest.T2Frame)

  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#fullSet"))
  @Timeout(120)
  def theWorkedFrameIs28sOnT3(arch: Arch): Unit = workedFrame(arch, 3, EncoderIdentityTest.T3Frame)

  private def corpus(arch: Arch, char: Boolean, pack: Boolean): Unit = {
    val records = EncoderCorpus.records()
    val batches = EncoderCorpus.batches(records)
    val sources = batches.map(EncoderCorpus.source)
    val paths = Seq("char" -> char, "pack" -> pack).collect { case (p, true) => p }
    val spec = RigSpec(s"EncoderIdentityTest-$arch-${paths.mkString("-")}", arch, "encode.lua",
      libraries = if (pack) Seq("ogpu_pack.lua") else Seq.empty,
      params = Map("char" -> char, "pack" -> pack), cases = i => sources.lift(i - 1))
    Rig.using(spec) { rig =>
      val finish = rig.awaitFinish(maxTicks = 2000)
      assertEquals(batches.size.toLong, rig.sink.first("batches").long(0), s"batches encoded; $finish")
      paths.foreach { path =>
        batches.indices.foreach { b =>
          val batch = batches(b)
          val expected = Bytes.concat(batch.map(_.bytes))
          val actual = rig.sink.joined(path, b + 1)
          Bytes.difference(expected, actual).foreach { d =>
            fail(s"$arch $path path, batch ${b + 1}: ${EncoderIdentityTest.locate(batch, d, expected, actual)}")
          }
        }
      }
      val bytes = records.map(_.bytes.length.toLong).sum
      Report.line(s"EncoderIdentityTest $arch ${paths.mkString(" and ")}: ${records.size} records (" +
        s"${EncoderCorpus.edges.size} edges, ${EncoderCorpus.cases} random from seed ${EncoderCorpus.seed}) in " +
        s"${batches.size} batches, $bytes B, equal to TestEncoder's")
    }
  }

  private def workedFrame(arch: Arch, tier: Int, frame: String): Unit = {
    val bpp = if (tier == 2) 1 else 2
    val spec = RigSpec(s"EncoderIdentityTest-$arch-T$tier", arch, "worked_frame.lua", tier = tier,
      params = Map("tier" -> tier), blobs = Map("sprite" -> EncoderIdentityTest.sprite(bpp)))
    Rig.using(spec) { rig =>
      rig.awaitFinish()
      val r = rig.sink.first("frame")
      assertEquals(0x08000000L, r.long(0), s"the new device's first handle (28:821); $r")
      val lua = r.bytes(1)
      Bytes.difference(TestEncoder.hex(frame), lua).foreach(d => fail(s"$arch T$tier: 28's bytes, $d"))
      assertArrayEquals(EncoderIdentityTest.encoded(tier), lua, s"$arch T$tier: TestEncoder's bytes")
      assertTrue(r.bool(2), s"submit; $r")
      assertTrue(r.isNil(4) && r.str(5) == "no renderer", s"present under NoRenderHost; $r")
      Report.line(s"EncoderIdentityTest $arch T$tier: the worked frame of 28:819-842, ${lua.length} B, is 28's")
    }
  }
}

object EncoderIdentityTest {
  /** 28:833-838, verbatim. */
  val T2Frame: String = "4F 47 50 55 01 00 00 00 | 01 00 00 00 00 | " +
    "30 0A 00 14 00 64 00 32 00 C4 00 00 00 | 31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01 | " +
    "33 08 00 B4 00 FF 00 00 00 00 00 00 00 01 00 02 00 48 69 | FF"

  /** 28:842's operands in 28 §5.2's layouts. */
  val T3Frame: String = "4F 47 50 55 01 00 00 00 | 01 00 00 00 FF | " +
    "30 0A 00 14 00 64 00 32 00 00 80 FF FF | 31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01 | " +
    "33 08 00 B4 00 FF FF FF FF 00 00 00 FF 01 00 02 00 48 69 | FF"

  /** The worked frame from TestEncoder, with 28:842's operands on T3. */
  def encoded(tier: Int): Array[Byte] =
    if (tier == 2) {
      TestEncoder.segment(TestEncoder.clear(0), TestEncoder.fill(10, 20, 100, 50, 196),
        TestEncoder.blit(0x08000000, 0, 0, 16, 16, 150, 40, 1), TestEncoder.text(8, 180, 255, 0, 1, 0, "Hi"))
    } else {
      TestEncoder.segment(TestEncoder.clear(0xFF000000), TestEncoder.fill(10, 20, 100, 50, 0xFFFF8000),
        TestEncoder.blit(0x08000000, 0, 0, 16, 16, 150, 40, 1),
        TestEncoder.text(8, 180, 0xFFFFFFFF, 0xFF000000, 1, 0, "Hi"))
    }

  /** A 16 x 16 sprite, rows top first: texel (i, j) is (i + 3j) % 5, times 0x0841 in rgb565 (as :core's test). */
  def sprite(bpp: Int): Array[Byte] = {
    val out = new Array[Byte](16 * 16 * bpp)
    for (j <- 0 until 16; i <- 0 until 16) {
      val v = (i + 3 * j) % 5
      if (bpp == 1) out(j * 16 + i) = v.toByte
      else {
        val c = v * 0x0841
        out((j * 16 + i) * 2) = c.toByte
        out((j * 16 + i) * 2 + 1) = (c >>> 8).toByte
      }
    }
    out
  }

  /** The record the first difference falls in, with its offset inside the batch and inside the record. */
  def locate(batch: Seq[Rec], difference: String, expected: Array[Byte], actual: Array[Byte]): String = {
    val n = math.min(expected.length, actual.length)
    var at = 0
    while (at < n && expected(at) == actual(at)) at += 1
    var start = 0
    val index = batch.indexWhere { r =>
      val size = r.bytes.length
      val inside = at < start + size
      if (!inside) start += size
      inside
    }
    if (index < 0) s"past the batch's records; $difference"
    else {
      val r = batch(index)
      s"record ${index + 1} of ${batch.size} ($r), its byte ${at - start}: Lua ${Bytes.window(actual, start)}, " +
        s"TestEncoder ${Bytes.window(r.bytes, 0)}; $difference"
    }
  }
}
