package io.github.mindbound.opengpu.ocelot

import io.github.mindbound.opengpu.core.cmd.ErrorCode
import io.github.mindbound.opengpu.core.device.{Charges => DeviceCharges, Statuses}
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig
import io.github.mindbound.opengpu.ocelot.Expect.{Message, Ok, Rejected}
import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue, fail}
import org.junit.jupiter.api.{Tag, Timeout}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
  * The device under NoRenderHost through real OC (RM:65's done-when; 28:106, :204; 24:87; S10 plan P14.4; amendments
  * I16), on the task's full set:
  *  - (a) `present()` after a valid submit returns `nil, "no renderer"`, and discards the open frame with the free
  *    deferred in it, which is appended, so the slot is free at once;
  *  - (b), (c) after a full-surface FILL is submitted and presented, so that a host that rendered would show it,
  *    readAll gives the cleared `frameId = 0` image: 64 000 zero bytes in 2 chunks on T2, 512 000 in 8 on T3 (28 C22);
  *  - (d) readPixels' region rows: "region exceeds 32768 bytes per call" (28:342) and "invalid region";
  *  - (e) validation parity: a submit of every stream error of 28 §3.6 a submit can reach, OVER_SUBMIT_BYTES (70 000 B)
  *    and the frame caps included, and every texture-call message of 28:343-345 that NoRenderHost can reach, each
  *    giving through Lua exactly what Java gives on `DeviceRig.noRenderer` with the same inputs (28:347; 24:228);
  *  - (f) H23 (28:1074): with live and deferred textures at 256 and no frame in flight, createTexture is "busy", and
  *    createTextureRetry presents first ("no renderer") and gets a handle.
  * It also runs on LuaJ, for correctness only (S10 decisions D5).
  */
@Tag("ocelot-pack")
@Tag("ocelot-instance")
@Tag("ocelot-luaj")
class NoRendererTest {
  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#fullSet"))
  @Timeout(120)
  def presentDiscardsTheFrameAndAppendsItsFrees(arch: Arch): Unit = {
    val spec = RigSpec(s"NoRendererTest-a-$arch", arch, "no_renderer.lua",
      params = Map("case" -> "present", "colour" -> 0xFFFF8000L))
    Rig.using(spec) { rig =>
      // Before the shutdown's computer.stopped, whose reset would free everything anyway
      rig.parked(1)
      val s = rig.gpu.device.snapshot()
      rig.release()
      rig.awaitFinish()
      val r = rig.sink.first("present")
      assertEquals(0x08000000L, r.long(0), s"createTexture; $r")
      assertTrue(r.bool(2) && r.bool(4), s"submit and free; $r")
      assertTrue(r.isNil(6), s"present's frame id; $r")
      assertEquals(Statuses.NO_RENDERER, r.str(7), s"present's status; $r")
      assertEquals(2L, s.epoch, s"the boot's reset only: $s")
      assertTrue(s.openSegments.isEmpty && s.deferredFrees.isEmpty, s"the open frame and its deferred frees: $s")
      assertTrue(s.live.isEmpty && s.masters.isEmpty, s"the texture is gone, its TEX_FREE folded: $s")
      assertEquals(1, s.generations()(0), s"slot 0's generation after the free: $s")
      assertEquals(0L, s.lastFrameId, "no frame was sealed")
    }
  }

  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#fullSet"))
  @Timeout(120)
  def readAllGivesTheClearedFrame0OnT2(arch: Arch): Unit = clearedFrame(arch, 2, 196L, 2)

  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#fullSet"))
  @Timeout(120)
  def readAllGivesTheClearedFrame0OnT3(arch: Arch): Unit = clearedFrame(arch, 3, 0xFFFF8000L, 8)

  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#fullSet"))
  @Timeout(120)
  def readPixelsRefusesWhatTheDeviceRefuses(arch: Arch): Unit = {
    val script = new CallScript
    val exceeds = Message("region exceeds 32768 bytes per call")
    val invalid = Message("invalid region")
    script.call("readPixels", "0", "0", "320", "103")(exceeds)
    script.call("readPixels", "0", "0", "320", "102")(Ok)
    script.call("readPixels", "0", "0", "1", "1")(Ok)
    script.call("readPixels", "319", "199", "1", "1")(Ok)
    Seq(Seq(-1, 0, 1, 1), Seq(0, -1, 1, 1), Seq(320, 0, 1, 1), Seq(0, 200, 1, 1), Seq(0, 0, 0, 1), Seq(0, 0, 1, 0),
      Seq(0, 0, 321, 1), Seq(0, 0, 1, 201), Seq(319, 0, 2, 1), Seq(0, 199, 1, 2), Seq(Int.MaxValue, 0, 1, 1),
      Seq(0, 0, Int.MaxValue, 1), Seq(Int.MinValue, 0, 1, 1), Seq(0, 0, -1, 1))
      .foreach(row => script.call("readPixels", row.map(_.toString): _*)(invalid))
    val outcomes = calls(arch, "d", 2, script)
    Report.line(s"NoRendererTest $arch region rows: ${outcomes.size} readPixels calls as on the Java device")
  }

  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#fullSet"))
  @Timeout(120)
  def callsValidateAsOnTheJavaDevice(arch: Arch): Unit = {
    val script = NoRendererTest.validation(rendering = false)
    val outcomes = calls(arch, "e", 2, script)
    val reached = NoRendererTest.assertCoverage(outcomes)
    Report.line(s"NoRendererTest $arch validation parity: ${outcomes.size} calls, $reached stream error codes and " +
      s"${NoRendererTest.TextureMessages.size} texture-call messages, as on the Java device")
  }

  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#fullSet"))
  @Timeout(120)
  def retryPresentsWhenNoFrameIsInFlight(arch: Arch): Unit = {
    val spec = RigSpec(s"NoRendererTest-f-$arch", arch, "no_renderer.lua",
      params = Map("case" -> "h23", "colour" -> 0xFFFF8000L))
    Rig.using(spec) { rig =>
      rig.parked(1)
      val s = rig.gpu.device.snapshot()
      rig.release()
      rig.awaitFinish()
      val r = rig.sink.first("h23")
      assertTrue(r.isNil(0), s"the first createTexture; $r")
      assertEquals(Statuses.BUSY, r.str(1), s"the first createTexture; $r")
      assertTrue(r.long(2) > 0, s"createTextureRetry's handle; $r")
      assertEquals(Seq(0L, 0L), Seq(r.long(3), r.long(4)), s"no frame was presented or confirmed; $r")
      assertEquals(256, s.live.size, s"255 left and the retry's: $s")
      assertTrue(s.openSegments.isEmpty && s.deferredFrees.isEmpty, s"the present discarded the frame: $s")
      assertTrue(rig.gpu.charges.snapshot.contains(Charges.consume(DeviceCharges.present(3))),
        s"the helper's present: ${rig.gpu.charges.snapshot.takeRight(4)}")
    }
  }

  private def clearedFrame(arch: Arch, tier: Int, colour: Long, chunks: Int): Unit = {
    val spec = RigSpec(s"NoRendererTest-T$tier-$arch", arch, "no_renderer.lua", tier = tier,
      params = Map("case" -> "readAll", "colour" -> colour))
    Rig.using(spec) { rig =>
      rig.awaitFinish()
      val p = rig.sink.first("present")
      assertTrue(p.bool(0), s"the FILL's submit; $p")
      assertTrue(p.isNil(2) && p.str(3) == Statuses.NO_RENDERER, s"present; $p")
      val (bytes, frames) = rig.sink.rows("screen")
      val size = rig.gpu.device.caps().surface.byteSize()
      assertEquals(size, bytes.length, "readAll's bytes")
      val first = bytes.indexWhere(_ != 0)
      assertTrue(first < 0, s"the cleared image has a non-zero byte at $first: ${Bytes.window(bytes, first)}")
      assertEquals(Seq.fill(chunks)(0L), frames, "readAll's chunks and their frame ids")
      Report.line(s"NoRendererTest $arch T$tier: present gave no renderer; readAll gave $size zero bytes of frame 0 " +
        s"in $chunks chunks")
    }
  }

  /** The script through calls.lua on a T`tier` device, replayed on `DeviceRig.noRenderer(tier)`; fails on a miss. */
  private def calls(arch: Arch, part: String, tier: Int, script: CallScript): Seq[CallReplay.Outcome] = {
    val source = script.source
    val spec = RigSpec(s"NoRendererTest-$part-$arch", arch, "calls.lua", tier = tier,
      cases = i => if (i == 1) Some(source) else None)
    Rig.using(spec) { rig =>
      rig.awaitFinish(maxTicks = 1000)
      val outcomes = CallReplay.replay(DeviceRig.noRenderer(tier), rig.sink, script.all)
      outcomes.find(!_.same).foreach(o => fail(s"$arch: $o"))
      outcomes.flatMap(_.missed).headOption.foreach(m => fail(s"$arch: $m"))
      outcomes
    }
  }
}

object NoRendererTest {
  /** The texture-call messages of 28:343-345 that NoRenderHost reaches; H2's "busy" needs a frame in the tail. */
  val TextureMessages: Seq[String] = Seq("invalid texture size", "unsupported format", "too many textures",
    "not enough video memory", "invalid texture region", "data must be 4 bytes", "invalid handle 0x00000000",
    "handle 0x40000000 has an unknown type", "handle 0x10000000 is a buffer, expected a texture",
    "handle 0x08000001 is not live", "handle 0x08000000 is not live")

  /**
    * That the calls reached every stream error code a submit can reach and every message of [[TextureMessages]];
    * returns the number of codes.
    */
  def assertCoverage(outcomes: Seq[CallReplay.Outcome]): Int = {
    val reached = outcomes.collect { case o if o.call.expect.isInstanceOf[Rejected] =>
      o.call.expect.asInstanceOf[Rejected].code
    }.toSet
    // WRONG_FORMAT needs a texture of the other format, which M0 cannot create (28:317; §6.2)
    assertEquals(ErrorCode.values.toSet - ErrorCode.WRONG_FORMAT, reached, "the stream errors a submit reaches")
    val messages = outcomes.collect { case o if o.call.expect.isInstanceOf[Message] =>
      o.call.expect.asInstanceOf[Message].text
    }.toSet
    TextureMessages.foreach(m => assertTrue(messages.contains(m), s"no call reaches \"$m\""))
    reached.size
  }

  /**
    * (e)'s calls on T2, in 28 §3.6's order: the stream errors, each frame cap reached in one submit and across two, the
    * frame bytes cap after two accepted segments, then the texture calls. Its three presents give "no renderer"
    * under NoRenderHost; with `rendering` (GlParityTest, P14.6) each gives a frame id, which both sides then wait for,
    * so the texture calls after them meet an empty tail as under NoRenderHost (Device.appendResource).
    */
  def validation(rendering: Boolean): CallScript = {
    val s = new CallScript
    val hdr = "E.HEADER"
    val end = "E.END"
    def seg(records: String*): String = (hdr +: records :+ end).mkString(" .. ")
    def submit(stream: String, code: ErrorCode, offset: Int): Unit = s.call("submit", stream)(Rejected(code, offset))
    def accept(stream: String): Unit = s.call("submit", stream)(Ok)
    var presents = 0
    def present(): Unit =
      if (rendering) {
        presents += 1
        s.call("present")(Ok, keep = s"frame$presents")
        s.await(s"frame$presents")
      } else s.call("present")(Message(Statuses.NO_RENDERER))
    val empty = "E.fill(0, 0, 0, 0, 0)"
    s.call("createTexture", "16", "16")(Ok, keep = "tex")
    submit("""E.rep("\000", 70000)""", ErrorCode.OVER_SUBMIT_BYTES, 0)
    submit("\"\"", ErrorCode.TRUNCATED_HEADER, 0)
    submit(Lua.str("OGPU\u0001\u0000\u0000"), ErrorCode.TRUNCATED_HEADER, 0)
    submit(Lua.str("XGPU\u0001\u0000\u0000\u0000") + " .. " + end, ErrorCode.BAD_MAGIC, 0)
    submit(Lua.str("OGPX\u0001\u0000\u0000\u0000") + " .. " + end, ErrorCode.BAD_MAGIC, 0)
    submit(Lua.str("OGPU\u0002\u0000\u0000\u0000") + " .. " + end, ErrorCode.BAD_VERSION, 4)
    submit(Lua.str("OGPU\u0000\u0000\u0000\u0000") + " .. " + end, ErrorCode.BAD_VERSION, 4)
    submit(Lua.str("OGPU\u0001\u0001\u0000\u0000") + " .. " + end, ErrorCode.BAD_FLAGS, 5)
    submit(Lua.str("OGPU\u0001\u0000\u0001\u0000") + " .. " + end, ErrorCode.BAD_RESERVED, 6)
    submit(Lua.str("OGPU\u0001\u0000\u0000\u0080".getBytes("ISO-8859-1")) + " .. " + end, ErrorCode.BAD_RESERVED, 6)
    submit(hdr, ErrorCode.MISSING_END, 8)
    submit(s"$hdr .. E.clear(0)", ErrorCode.MISSING_END, 13)
    submit(s"""$hdr .. $end .. "\\000"""", ErrorCode.TRAILING_BYTES, 8)
    submit(seg("\"\\002\""), ErrorCode.UNKNOWN_OPCODE, 8)
    submit(seg("\"\\254\""), ErrorCode.UNKNOWN_OPCODE, 8)
    submit(s"$hdr .. string.sub(E.fill(0, 0, 1, 1, 1), 1, 12)", ErrorCode.TRUNCATED_RECORD, 8)
    submit(seg("""E.text(0, 0, 1, 0, 0, 0, E.rep("a", 1025))"""), ErrorCode.TEXT_TOO_LONG, 8)
    submit(s"""$hdr .. string.sub(E.text(0, 0, 1, 0, 0, 0, "abc"), 1, 19)""", ErrorCode.TRUNCATED_RECORD, 8)
    submit(seg("E.clear(256)"), ErrorCode.BAD_COLOUR, 8)
    submit(seg("E.clear(0)", "E.fill(0, 0, 1, 1, 256)"), ErrorCode.BAD_COLOUR, 13)
    submit(seg("E.text(0, 0, 256, 0, 0, 0, \"a\")"), ErrorCode.BAD_COLOUR, 8)
    submit(seg("E.text(0, 0, 1, 4294967295, 0, 0, \"a\")"), ErrorCode.BAD_COLOUR, 8)
    submit(seg("E.blit(0, 0, 0, 1, 1, 0, 0, 0)"), ErrorCode.BAD_HANDLE, 8)
    submit(seg("E.blit(2147483648, 0, 0, 1, 1, 0, 0, 0)"), ErrorCode.BAD_HANDLE, 8)
    submit(seg(s"E.blit(${0x40000000}, 0, 0, 1, 1, 0, 0, 0)"), ErrorCode.UNKNOWN_TYPE, 8)
    submit(seg(s"E.blit(${0x10000000}, 0, 0, 1, 1, 0, 0, 0)"), ErrorCode.WRONG_TYPE, 8)
    submit(seg(s"E.blit(${0x08000001}, 0, 0, 1, 1, 0, 0, 0)"), ErrorCode.STALE_HANDLE, 8)
    submit(seg(s"E.blit(${0x08010000}, 0, 0, 1, 1, 0, 0, 0)"), ErrorCode.STALE_HANDLE, 8)
    submit(seg("E.blit(V.tex, 0, 0, 1, 1, 0, 0, 2)"), ErrorCode.BAD_FLAGS_FIELD, 8)
    submit(seg("E.text(0, 0, 1, 0, 2, 0, \"a\")"), ErrorCode.BAD_FLAGS_FIELD, 8)
    submit(seg("E.text(0, 0, 1, 0, 0, 1, \"a\")"), ErrorCode.UNKNOWN_FONT, 8)
    submit(seg("E.text(0, 0, 1, 0, 0, 0, \"\\255\")"), ErrorCode.BAD_UTF8, 8)
    submit(seg("E.text(0, 0, 1, 0, 0, 0, \"a\\192\\128\")"), ErrorCode.BAD_UTF8, 8)
    submit(seg("E.text(0, 0, 1, 0, 0, 0, \"\\010\")"), ErrorCode.CONTROL_CHAR, 8)
    // The frame caps (T2: 4 096 records, 1 024 000 px, 131 072 B; 28 §8.1) in one submit, then across two
    submit(seg(s"E.rep($empty, 4097)"), ErrorCode.OVER_FRAME_RECORDS, 8 + 4096 * 13)
    submit(seg("E.rep(E.clear(0), 17)"), ErrorCode.OVER_FRAME_FILL, 8 + 16 * 5)
    accept(seg(s"E.rep($empty, 4000)"))
    submit(seg(s"E.rep($empty, 97)"), ErrorCode.OVER_FRAME_RECORDS, 8 + 96 * 13)
    present()
    accept(seg("E.rep(E.clear(0), 16)"))
    submit(seg("E.clear(0)"), ErrorCode.OVER_FRAME_FILL, 8)
    present()
    // 51 TEXT records of 256 four-byte codepoints (1 041 B and 19 968 px each), then 1 500 empty fills: 72 609 B under
    // every cap; a 60 000 B submit then passes 131 072 before anything else is read
    accept(seg("E.rep(E.text(0, 0, 1, 0, 0, 0, E.rep(\"\\240\\159\\152\\128\", 256)), 51)"))
    accept(seg(s"E.rep($empty, 1500)"))
    submit("""E.rep("\000", 60000)""", ErrorCode.OVER_FRAME_BYTES, 0)
    present()
    // createTexture (28:343)
    s.call("createTexture", "0", "1")(Message("invalid texture size"))
    s.call("createTexture", "1", "1025")(Message("invalid texture size"))
    s.call("createTexture", "1", "1", "\"rgb565\"")(Message("unsupported format"))
    s.call("createTexture", "1", "1", "\"index8\"")(Ok, keep = "named")
    s.call("free", "V.named")(Ok)
    // 1 047 552 B of T2's 1 048 576 beside the 16 x 16 texture, a charge of about 4 against T2's credit of 1, so
    // bitblt's two-phase pattern (28:713); 768 B are left
    s.call("createTexture", "1024", "1023")(Ok, keep = "big")
    s.call("createTexture", "32", "32")(Message("not enough video memory"))
    s.call("free", "V.big")(Ok)
    s.loop(255, "createTexture", "1", "1")(Ok, keep = "t")
    s.call("createTexture", "1", "1")(Message("too many textures"))
    s.loop(255, "free", "V[\"t\" .. k]")(Ok)
    // updateTexture (28:344)
    val pixel = "\"\\000\""
    s.call("updateTexture", "0", "0", "0", "1", "1", pixel)(Message("invalid handle 0x00000000"))
    s.call("updateTexture", s"${0x40000000}", "0", "0", "1", "1", pixel)(
      Message("handle 0x40000000 has an unknown type"))
    s.call("updateTexture", s"${0x10000000}", "0", "0", "1", "1", pixel)(
      Message("handle 0x10000000 is a buffer, expected a texture"))
    s.call("updateTexture", s"${0x08000001}", "0", "0", "1", "1", pixel)(Message("handle 0x08000001 is not live"))
    s.call("updateTexture", "V.tex", "16", "0", "1", "1", pixel)(Message("invalid texture region"))
    s.call("updateTexture", "V.tex", "0", "0", "0", "1", "\"\"")(Message("invalid texture region"))
    s.call("updateTexture", "V.tex", "0", "0", "2", "2", "E.rep(\"\\000\", 3)")(Message("data must be 4 bytes"))
    s.call("updateTexture", "V.tex", "0", "0", "2", "2", "E.rep(\"\\007\", 4)")(Ok)
    // free (28:345)
    s.call("free", "0")(Message("invalid handle 0x00000000"))
    s.call("free", s"${0x40000000}")(Message("handle 0x40000000 has an unknown type"))
    s.call("free", s"${0x10000000}")(Message("handle 0x10000000 is a buffer, expected a texture"))
    s.call("free", s"${0x08000001}")(Message("handle 0x08000001 is not live"))
    s.call("free", "V.tex")(Ok)
    s.call("free", "V.tex")(Message("handle 0x08000000 is not live"))
    s
  }
}
