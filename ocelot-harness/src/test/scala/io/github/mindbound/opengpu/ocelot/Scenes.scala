package io.github.mindbound.opengpu.ocelot

import java.util.SplittableRandom

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import io.github.mindbound.opengpu.core.SurfaceDesc
import io.github.mindbound.opengpu.core.device.{DeviceCaps, Statuses}
import io.github.mindbound.opengpu.core.fixtures.TestEncoder
import io.github.mindbound.opengpu.core.fixtures.device.{DeviceRig, OracleRecordRenderer}
import io.github.mindbound.opengpu.core.font.GlyphTables
import io.github.mindbound.opengpu.core.render.FakeRenderHost
import io.github.mindbound.opengpu.ocelot.Rec.{Blit, Clear, Fill, Named}
import org.junit.jupiter.params.provider.Arguments

/** One step of a scene (S10 plan P14.8): scene.lua's S calls and the Java replay's. */
sealed trait Step

object Step {
  final case class Create(name: String, w: Int, h: Int) extends Step

  /** The whole region's data, rows top first, in the surface's format. */
  final case class Update(name: String, x: Int, y: Int, w: Int, h: Int, data: Array[Byte]) extends Step

  final case class Submit(records: Seq[Rec]) extends Step

  case object Present extends Step

  /**
    * Waits for the newest frame presented: by polling on the fake host, by parking for Java's bounded wait on the GL
    * host (LuaFboTest), and not at all under NoRenderHost. Each one has its number in the scene, from 1.
    */
  case object Await extends Step

  /** Holds the host's pumps, or releases them (`ogpu_harness.hold`). */
  final case class Hold(on: Boolean) extends Step

  final case class Read(tag: String) extends Step

  final case class Free(name: String) extends Step

  /** `computer.shutdown(true)`: the machine's `computer.stopped` and `computer.started` each reset the device (P7.4). */
  case object Reboot extends Step
}

/**
  * A scene: data that scene.lua runs as calls of 28 §9.2's builders, and that the Java replay builds with TestEncoder
  * on a DeviceRig over the tier-E oracle (S10 plan P14.8). `phases(0)` runs on the machine's first boot, `phases(1)`
  * after a [[Step.Reboot]].
  */
final case class Scene(id: Int, title: String, tier: Int, phases: Seq[Seq[Step]]) {
  /** The data of every update, by the blob name the Lua side reads it under. */
  def blobs: Map[String, Array[Byte]] = updates.map { case (u, name) => name -> u.data }.toMap

  /** Case batch 1 for scene.lua: a function of (S, boot). */
  def source: String = {
    val names = updates.toMap
    var awaits = 0
    def lua(step: Step): String = step match {
      case Step.Create(n, w, h) => s"S.create(${Lua.str(n)}, $w, $h)"
      case u @ Step.Update(n, x, y, w, h, _) => s"S.update(${Lua.str(n)}, $x, $y, $w, $h, ${Lua.str(names(u))})"
      case Step.Submit(records) => records.map(_.lua("S.E")).mkString("S.submit(S.segment({\n  ", ",\n  ", "}))")
      case Step.Present => "S.present()"
      case Step.Await =>
        awaits += 1
        s"S.wait($awaits)"
      case Step.Hold(on) => s"S.hold($on)"
      case Step.Read(tag) => s"S.readAll(${Lua.str(tag)})"
      case Step.Free(n) => s"S.free(${Lua.str(n)})"
      case Step.Reboot => "S.reboot()"
    }
    val body = phases.zipWithIndex.map { case (steps, p) =>
      s"${if (p == 0) "if" else "elseif"} boot == ${p + 1} then\n" + steps.map(lua).mkString("\n")
    }.mkString("\n")
    s"return function(S, boot)\n$body\nelse\nerror(\"scene $id has no boot \" .. tostring(boot), 0)\nend\nend\n"
  }

  def reads: Seq[String] = phases.flatten.collect { case Step.Read(tag) => tag }

  /** The scene's [[Step.Await]]s, numbered from 1 in step order over both boots. */
  def awaits: Int = phases.flatten.count(_ == Step.Await)

  /** Each read's tag with the number of the last Await before it, the frame the read shows; 0 for none. */
  def readAwaits: Seq[(String, Int)] = {
    var last = 0
    phases.flatten.flatMap {
      case Step.Await =>
        last += 1
        None
      case Step.Read(tag) => Some(tag -> last)
      case _ => None
    }
  }

  override def toString: String = s"$id $title"

  private def updates: Seq[(Step.Update, String)] =
    phases.flatten.collect { case u: Step.Update => u }.zipWithIndex.map { case (u, i) => u -> s"u${i + 1}" }
}

/** What the Java replay of a scene gave: the handles in creation order, and each read's image and frame id. */
final case class Replay(handles: Seq[(String, Long)], reads: Map[String, (Array[Byte], Long)])

/**
  * The ten scenes of S10 plan P14.8, and their Java replay. The fake host and the replay both run the oracle with font
  * 0's real glyph table, so TEXT draws misc-fixed 6x13 (28 §7.3).
  */
object Scenes {
  import Step._

  def all: Seq[Scene] = Seq(workedT2, workedT3, clipping, keyedAndTransparent, twoFramesInFlight, threeSubmits, font0,
    generated(8, 2), generated(9, 3), reboot)

  /** (arch, scene) for every arch of the task, for SceneOracleTest's `@MethodSource`. */
  def cases(): java.util.List[Arguments] =
    (for (arch <- Archs.running; scene <- all) yield Arguments.of(arch, scene)).asJava

  /**
    * (arch, scene) for the task's full set, for LuaFboTest's and SceneOracleTest's NoRenderHost pass: 5.3 and 5.2 on
    * the pack legs, 5.4 on the instance leg (S10 decisions D4); never empty but on a narrowed run (Archs.gating).
    */
  def fullCases(): java.util.List[Arguments] =
    (for (arch <- Archs.gating("the full set", Archs.full); scene <- all) yield Arguments.of(arch, scene)).asJava

  def surface(tier: Int): SurfaceDesc = DeviceCaps.forTier(tier).surface

  /**
    * The scene replayed in Java: a DeviceRig on a FakeRenderHost over the oracle, the RESET of the machine's first
    * `computer.started` first (P7.4), each segment from TestEncoder, and each wait a host step, whose readback folds at
    * once. The image after a frame does not depend on how the host grouped its records into steps, since it runs them
    * in offered order, so this replay's single step per wait gives the rig's image however often the ticker pumped.
    */
  def replay(scene: Scene): Replay = {
    val host = new FakeRenderHost(new OracleRecordRenderer(GlyphTables.font0()))
    val rig = DeviceRig.on(DeviceCaps.forTier(scene.tier), host, () => host.step())
    val handles = mutable.LinkedHashMap.empty[String, Int]
    val created = mutable.ArrayBuffer.empty[(String, Long)]
    val reads = mutable.Map.empty[String, (Array[Byte], Long)]
    rig.device.reset()
    scene.phases.flatten.foreach {
      case Create(n, w, h) =>
        val t = rig.create(w, h)
        handles(n) = t
        created += n -> t.toLong
      case Update(n, x, y, w, h, data) => rig.update(handles(n), x, y, w, h, data)
      case Submit(records) => rig.submit(TestEncoder.segment(records.map(_.bytes(handles)): _*))
      case Present => rig.present()
      case Await => rig.step()
      case Hold(_) =>
      case Read(tag) => reads(tag) = (rig.screen(), rig.snapshot().confirmedFrameId)
      case Free(n) => rig.free(handles(n))
      case Reboot =>
        rig.device.reset()
        rig.device.reset()
        rig.step()
    }
    Replay(created.toList, reads.toMap)
  }

  /** The handles the same calls get under NoRenderHost, where the open frame is discarded at every present. */
  def noRendererHandles(scene: Scene): Seq[(String, Long)] = {
    val rig = DeviceRig.noRenderer(scene.tier)
    val handles = mutable.LinkedHashMap.empty[String, Int]
    val created = mutable.ArrayBuffer.empty[(String, Long)]
    rig.device.reset()
    scene.phases.flatten.foreach {
      case Create(n, w, h) =>
        val t = rig.create(w, h)
        handles(n) = t
        created += n -> t.toLong
      case Update(n, x, y, w, h, data) => rig.update(handles(n), x, y, w, h, data)
      case Submit(records) => rig.submit(TestEncoder.segment(records.map(_.bytes(handles)): _*))
      case Present =>
        val r = rig.call(b => rig.device.present(b))
        if (r.isOk || r.message() != Statuses.NO_RENDERER) throw new IllegalStateException(s"present gave $r")
      case Free(n) => rig.free(handles(n))
      case Reboot =>
        rig.device.reset()
        rig.device.reset()
      case _ =>
    }
    created.toList
  }

  /** A w x h texture's data with texel (i, j) = f(i, j), rows top first, 1 B (index8) or a LE u16 (rgb565). */
  def texels(tier: Int, w: Int, h: Int)(f: (Int, Int) => Int): Array[Byte] = {
    val bpp = surface(tier).bytesPerPixel()
    val out = new Array[Byte](w * h * bpp)
    for (j <- 0 until h; i <- 0 until w) {
      val v = f(i, j)
      out((j * w + i) * bpp) = v.toByte
      if (bpp == 2) out((j * w + i) * 2 + 1) = (v >>> 8).toByte
    }
    out
  }

  private def text(x: Int, y: Int, fg: Int, bg: Int, flags: Int, s: String): Rec = Rec.text(x, y, fg, bg, flags, 0, s)

  private def t = Named("t")

  /** 1: 28 §9.4's worked frame on T2, through the oracle. */
  private def workedT2 = Scene(1, "worked frame T2", 2, Seq(Seq(
    Create("t", 16, 16), Update("t", 0, 0, 16, 16, EncoderIdentityTest.sprite(1)),
    Submit(Seq(Clear(0), Fill(10, 20, 100, 50, 196), Blit(t, 0, 0, 16, 16, 150, 40, 1), text(8, 180, 255, 0, 1, "Hi"))),
    Present, Await, Read("frame"))))

  /** 2: the same frame on T3 with 28:842's operands. */
  private def workedT3 = Scene(2, "worked frame T3", 3, Seq(Seq(
    Create("t", 16, 16), Update("t", 0, 0, 16, 16, EncoderIdentityTest.sprite(2)),
    Submit(Seq(Clear(0xFF000000), Fill(10, 20, 100, 50, 0xFFFF8000), Blit(t, 0, 0, 16, 16, 150, 40, 1),
      text(8, 180, 0xFFFFFFFF, 0xFF000000, 1, "Hi"))),
    Present, Await, Read("frame"))))

  /** 3: fills, blits and text off every edge of a T2 surface, and blits whose source overhangs the texture. */
  private def clipping = Scene(3, "clipping T2", 2, Seq(Seq(
    Create("t", 16, 16), Update("t", 0, 0, 16, 16, texels(2, 16, 16)((i, j) => 1 + (i * 16 + j) % 200)),
    Submit(Seq(Clear(1), Fill(-10, -10, 30, 30, 5), Fill(300, 180, 50, 50, 6), Fill(-30000, 50, 30100, 10, 8),
      Fill(310, -5, 32767, 10, 9), Fill(-5, 190, 10, 32767, 10),
      Blit(t, 0, 0, 16, 16, -8, -8, 0), Blit(t, 0, 0, 16, 16, 312, 192, 0), Blit(t, 4, 4, 16, 16, 100, 100, 0),
      Blit(t, -4, -4, 16, 16, 140, 100, 0), Blit(t, 0, 0, 16, 16, -32768, 0, 0),
      text(-3, -5, 10, 2, 0, "Clip"), text(296, 190, 11, 3, 0, "edge!"), text(-12, 100, 12, 4, 0, "left"))),
    Present, Await, Read("frame"))))

  /** 4: KEY0 blits and TRANSPARENT_BG text over two fills, beside their opaque forms, on T2. */
  private def keyedAndTransparent = Scene(4, "KEY0 and TRANSPARENT_BG T2", 2, Seq(Seq(
    Create("t", 16, 16), Update("t", 0, 0, 16, 16, texels(2, 16, 16)((i, j) => (i + j) % 3 * 70)),
    Submit(Seq(Clear(0), Fill(0, 0, 160, 200, 20), Fill(160, 0, 160, 200, 40),
      Blit(t, 0, 0, 16, 16, 10, 10, 1), Blit(t, 0, 0, 16, 16, 30, 10, 0), Blit(t, 0, 0, 16, 16, 170, 10, 1),
      Blit(t, 0, 0, 16, 16, 150, 10, 1), text(10, 50, 255, 0, 1, "KEY0 over fill"), text(10, 70, 255, 0, 0, "opaque"),
      text(150, 90, 250, 5, 1, "transparent bg"), text(150, 110, 250, 5, 0, "opaque bg"))),
    Present, Await, Read("frame"))))

  /**
    * 5: two frames in flight with an updateTexture between them (28:144), the pumps held so both frames are
    * unconfirmed: frame 1 blits the old texels on the left, the update joins the tail behind it, frame 2 blits the new
    * ones on the right.
    */
  private def twoFramesInFlight = Scene(5, "two frames in flight T2", 2, Seq(Seq(
    Create("t", 16, 16), Update("t", 0, 0, 16, 16, texels(2, 16, 16)((_, _) => 3)),
    Hold(true),
    Submit(Seq(Clear(0), Blit(t, 0, 0, 16, 16, 20, 20, 0))), Present,
    Update("t", 0, 0, 16, 16, texels(2, 16, 16)((i, j) => 9 + (i ^ j))),
    Submit(Seq(Blit(t, 0, 0, 16, 16, 200, 20, 0))), Present,
    Hold(false), Await, Read("frame"))))

  /** 6: one T3 frame from three submits, with colours at rgb565's quantization edges (28:495). */
  private def threeSubmits = Scene(6, "three submits T3", 3, Seq(Seq(
    Submit(Seq(Clear(0xFF000000), Fill(0, 0, 320, 200, 0xFFFFFFFF))),
    Submit(Seq(Fill(320, 0, 320, 200, 0xFF0000FF), Fill(0, 200, 320, 200, 0xFF00FF00))),
    Submit(Seq(Fill(320, 200, 320, 200, 0xFFFF0000), Fill(10, 10, 40, 40, 0xFF040404), Fill(60, 10, 40, 40, 0xFF050505),
      Fill(110, 10, 40, 40, 0xFF020202), Fill(160, 10, 40, 40, 0xFF030303), Fill(210, 10, 40, 40, 0xFFFBFBFB),
      Fill(260, 10, 40, 40, 0xFFFAFAFA), Fill(330, 10, 40, 40, 0xFFFDFDFD), Fill(380, 10, 40, 40, 0xFFFCFCFC),
      text(10, 60, 0xFF7F7F7F, 0xFF808080, 0, "rgb565 edges"))),
    Present, Await, Read("frame"))))

  /** 7: font 0's glyphs on T2: printable ASCII, Latin-1, U+20AC, and codepoints with no glyph, which draw the box. */
  private def font0 = Scene(7, "font 0 text T2", 2, Seq(Seq(
    Submit(Seq(Clear(17), text(0, 0, 255, 17, 0, " !\"#$%&'()*+,-./0123456789:;<=>?"),
      text(0, 13, 254, 17, 0, "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_"),
      text(0, 26, 253, 17, 0, "`abcdefghijklmnopqrstuvwxyz{|}~"),
      text(0, 39, 200, 17, 0, "Latin-1: àéîõü ß ÆØÅ ¿¡ «»"),
      text(0, 52, 201, 0, 1, "€ and no glyph: 一 😀"),
      text(3, 70, 230, 40, 0, "font 0 is misc-fixed 6x13"))),
    Present, Await, Read("frame"))))

  /**
    * 8 and 9: a scene drawn from the seed (`opengpu.ocelot.seed`): three textures of random texels, two frames of
    * random valid records within the frame caps, and a partial update between them.
    */
  private def generated(id: Int, tier: Int): Scene = {
    val r = new SplittableRandom(EncoderCorpus.seed * 31 + id)
    val s = surface(tier)
    val limits = DeviceCaps.forTier(tier).limits
    def colour(): Int = if (tier == 2) r.nextInt(256) else 0xFF000000 | r.nextInt(1 << 24)
    def texel(): Int = if (r.nextInt(5) == 0) 0 else if (tier == 2) 1 + r.nextInt(255) else 1 + r.nextInt(0xFFFF)
    val sizes = Seq.fill(3)((1 + r.nextInt(48), 1 + r.nextInt(48)))
    val names = Seq("a", "b", "c")
    def string(): String = {
      val b = new java.lang.StringBuilder
      (0 until r.nextInt(25)).foreach { _ =>
        r.nextInt(12) match {
          case 0 => b.append('é')
          case 1 => b.append('€')
          case _ => b.append((0x20 + r.nextInt(0x5F)).toChar)
        }
      }
      b.toString
    }
    def records(): Seq[Rec] = {
      val out = mutable.ArrayBuffer.empty[Rec]
      var fill = 0L
      (0 until 40).foreach { _ =>
        val (rec, area) = r.nextInt(10) match {
          case 0 => (Clear(colour()), s.width.toLong * s.height)
          case 1 | 2 | 3 =>
            val w = r.nextInt(s.width / 2)
            val h = r.nextInt(s.height / 2)
            (Fill(r.nextInt(s.width + 30) - 30, r.nextInt(s.height + 30) - 30, w, h, colour()), w.toLong * h)
          case 4 | 5 | 6 =>
            val k = r.nextInt(3)
            val (tw, th) = sizes(k)
            val w = r.nextInt(49)
            val h = r.nextInt(49)
            (Blit(Named(names(k)), r.nextInt(tw + 8) - 4, r.nextInt(th + 8) - 4, w, h, r.nextInt(s.width + 20) - 20,
              r.nextInt(s.height + 20) - 20, r.nextInt(2)), w.toLong * h)
          case _ =>
            val str = string()
            (text(r.nextInt(s.width + 30) - 30, r.nextInt(s.height + 13) - 13, colour(), colour(), r.nextInt(2), str),
              78L * str.codePointCount(0, str.length))
        }
        // Each area is at least what validation counts (28:284), so the frame stays within the fill cap
        if (fill + area <= limits.frameFill) {
          out += rec
          fill += area
        }
      }
      out.toList
    }
    val creates = names.zip(sizes).flatMap { case (n, (w, h)) =>
      Seq(Create(n, w, h), Update(n, 0, 0, w, h, texels(tier, w, h)((_, _) => texel())))
    }
    val (pw, ph) = sizes(1)
    val partial = Update("b", 0, 0, (pw + 1) / 2, ph, texels(tier, (pw + 1) / 2, ph)((_, _) => texel()))
    Scene(id, s"generated T$tier, seed ${EncoderCorpus.seed}", tier, Seq(creates ++ Seq(
      Submit(records()), Present, Await, Read("a"), partial, Submit(records()), Present, Await, Read("b"))))
  }

  /**
    * 10: a reboot of a T2 machine: `computer.shutdown(true)`, so `computer.stopped` and `computer.started`, each a
    * RESET (P7.4) [proposal]. After the reboot the image is K's, with K's frame id, the textures are gone, and frame
    * ids go on from K.
    */
  private def reboot = Scene(10, "reboot T2", 2, Seq(
    Seq(Create("t", 16, 16), Update("t", 0, 0, 16, 16, texels(2, 16, 16)((i, j) => 100 + i + j)),
      Submit(Seq(Clear(3), Blit(t, 0, 0, 16, 16, 50, 50, 0), text(10, 10, 255, 3, 0, "before the reboot"))),
      Present, Await, Read("before"), Reboot),
    Seq(Read("after"), Create("u", 8, 8), Update("u", 0, 0, 8, 8, texels(2, 8, 8)((i, j) => 200 + i * j)),
      Submit(Seq(Fill(100, 100, 50, 50, 77), Blit(Named("u"), 0, 0, 8, 8, 10, 150, 0),
        text(10, 30, 254, 3, 1, "after the reboot"))),
      Present, Await, Read("after2"))))
}
