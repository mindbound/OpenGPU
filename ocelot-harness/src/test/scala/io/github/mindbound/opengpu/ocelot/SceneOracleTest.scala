package io.github.mindbound.opengpu.ocelot

import scala.jdk.CollectionConverters._

import io.github.mindbound.opengpu.core.device.Statuses
import io.github.mindbound.opengpu.core.fixtures.device.{OracleRecordRenderer, RecordText}
import io.github.mindbound.opengpu.core.font.GlyphTables
import io.github.mindbound.opengpu.core.render.{FakeRenderHost, NoRenderHost, RenderHost}
import org.junit.jupiter.api.Assertions.{assertEquals, assertNull, assertTrue, fail}
import org.junit.jupiter.api.{Tag, Timeout}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
  * The ten scenes of [[Scenes]] through real OC on the pack's archs (S10 plan P14.8 [proposal; P11.2]), so that CI has
  * a check from Lua to pixels without GL and a GL failure can be told from an OC or Lua one:
  *  - on FakeRenderHost over the tier-E oracle with font 0, LuaFboTest's (1), (3), (5) and (7): Lua's readAll chunks,
  *    each of frame F, equal the host's delivered readback of F; they equal the Java replay's image, TestEncoder's
  *    frames on the oracle; `"done"` was recorded for F; `lastError` is null and the boot's architecture checks held;
  *  - under NoRenderHost, the same scripts (23:190; S10 amendments F9): every present gives `nil, "no renderer"`,
  *    readAll gives zeros with frame id 0, and the texture calls give the handles Java's device gives. This pass also
  *    runs on 5.4, on the instance leg's full set, since the scripts' no-renderer branch runs nowhere else there
  *    (S10 decisions D4; review P3); on GL, LuaFboTest covers what the fake host does.
  * Both check that the handles Lua got are the replay's.
  */
@Tag("ocelot-pack")
class SceneOracleTest {
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Scenes#cases"))
  @Timeout(120)
  def aSceneThroughOcIsTheOraclesImage(arch: Arch, scene: Scene): Unit = {
    val fake = new FakeRenderHost(new OracleRecordRenderer(GlyphTables.font0()))
    val replay = Scenes.replay(scene)
    Rig.using(spec(arch, scene, "fake", fake, () => fake.step())) { rig =>
      rig.awaitFinish()
      assertNull(rig.machine.lastError, "lastError")
      assertEquals(replay.handles, created(rig), "the handles Lua got, in order")
      val key = rig.gpu.device.key()
      val readbacks = fake.readbacks(key).asScala
      scene.reads.foreach { tag =>
        val (expected, frame) = replay.reads(tag)
        val (bytes, frames) = rig.sink.rows(tag)
        assertTrue(frames.forall(_ == frame), s"$scene $tag: chunk frame ids ${frames.mkString(", ")}, not $frame")
        Bytes.difference(expected, bytes).foreach(d => fail(s"$scene $tag against the Java replay: $d"))
        val delivered = readbacks.filter(_.frameId == frame).lastOption
          .getOrElse(fail(s"$scene $tag: the host delivered no readback of frame $frame: ${readbacks.mkString(", ")}"))
        Bytes.difference(delivered.pixels, bytes).foreach(d => fail(s"$scene $tag against readback $frame: $d"))
        assertTrue(rig.gpu.statuses.events().contains(s"done $frame"),
          s"$scene $tag: no done for frame $frame: ${rig.gpu.statuses.events()}")
      }
      if (scene.id == 5) {
        // The update was offered between the two frames (28:144); the RESETs are the boot's computer.started and the
        // shutdown's computer.stopped (P7.4)
        val kinds = fake.offered(key).asScala.map(r => RecordText.of(r).takeWhile(_ != ' '))
        assertEquals(Seq("RESET", "TEX_CREATE", "TEX_UPDATE", "FRAME", "TEX_UPDATE", "FRAME", "RESET"), kinds,
          "the records offered")
      }
      Report.line(s"SceneOracleTest $arch scene $scene: ${scene.reads.size} reads equal the replay and the host's " +
        s"readbacks, frames ${scene.reads.map(replay.reads(_)._2).mkString(", ")}")
    }
  }

  @ParameterizedTest(name = "{0} {1}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Scenes#fullCases"))
  @Tag("ocelot-instance")
  @Timeout(120)
  def aSceneUnderNoRenderHostPresentsNothing(arch: Arch, scene: Scene): Unit = {
    val handles = Scenes.noRendererHandles(scene)
    Rig.using(spec(arch, scene, "none", NoRenderHost.INSTANCE, () => ())) {
      rig =>
        rig.awaitFinish()
        assertNull(rig.machine.lastError, "lastError")
        assertEquals(handles, created(rig), "the handles Lua got, in order")
        val presents = rig.sink.all("presented")
        assertTrue(presents.nonEmpty && presents.forall(p => p.isNil(0) && p.str(1) == Statuses.NO_RENDERER),
          s"every present: ${presents.mkString("; ")}")
        val size = Scenes.surface(scene.tier).byteSize()
        scene.reads.foreach { tag =>
          val (bytes, frames) = rig.sink.rows(tag)
          assertEquals(size, bytes.length, s"$scene $tag: bytes")
          val first = bytes.indexWhere(_ != 0)
          assertTrue(first < 0, s"$scene $tag: a non-zero byte at $first: ${Bytes.window(bytes, first)}")
          assertTrue(frames.forall(_ == 0L), s"$scene $tag: chunk frame ids ${frames.mkString(", ")}")
        }
        Report.line(s"SceneOracleTest $arch scene $scene under NoRenderHost: ${presents.size} presents gave no " +
          s"renderer, ${scene.reads.size} reads gave frame 0's zeros")
    }
  }

  private def spec(arch: Arch, scene: Scene, host: String, render: RenderHost,
      pump: () => Unit): RigSpec = {
    val source = scene.source
    RigSpec(s"SceneOracleTest-$arch-${scene.id}-$host", arch, "scene.lua", tier = scene.tier, host = render, pump = pump,
      blobs = scene.blobs, cases = i => if (i == 1) Some(source) else None)
  }

  /** scene.lua's created records: (name, handle), in order, over both boots. */
  private def created(rig: Rig): Seq[(String, Long)] = rig.sink.all("created").map(r => r.str(0) -> r.long(1))
}
