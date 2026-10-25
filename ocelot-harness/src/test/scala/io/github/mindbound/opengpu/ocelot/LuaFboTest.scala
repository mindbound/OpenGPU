package io.github.mindbound.opengpu.ocelot

import scala.collection.mutable

import io.github.mindbound.opengpu.gltestkit.{GlTest, GlThread}
import org.junit.jupiter.api.Assertions.{assertEquals, assertNull, assertTrue, fail}
import org.junit.jupiter.api.{Tag, Timeout}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
  * Option 2's core contract through real OC (23:184-188; RM:65's "Lua bytes == FBO bytes"; 24:248; S10 plan P14.8):
  * the ten scenes of [[Scenes]] on GlTestHost, 5.3 and 5.2 on the pack's GL leg (ocelotGlTest) and 5.4 on the
  * instance's (S10 decisions D4). Each present holds the host's pumps, and at each Await the script parks while Java
  * waits for that frame's readback as a bounded wait (05 §3): the condition is the readback's arrival at the device,
  * the tick cap N is [[Cadence.readbackCapTicks]], derived from the pumps per tick (S10 amendments F10), and a timeout
  * names the cap it hit (23:188 step 4; P10.2). Exactly, with no tolerance (23:186), each read:
  *  - (1) has every readAll chunk of frame F, and equals the readback of F the GL host delivered (CapturingHost);
  *  - (2) equals `surfacePixels`, a second read of the FBO through the production calls, taken at F's Await;
  *  - (3) equals the Java replay, TestEncoder's frames on the tier-E oracle with font 0, the scene's tier golden, and
  *    the tee's shadow oracle found no readback that differs (23:187);
  *  - (5) had `"done"` recorded for F.
  * And (4) every readback arrived within N ticks, (6) no hygiene check found anything and the ledger was empty at
  * close (GlRigHost), and (7) `lastError` is null, with the boot's architecture checks.
  */
@GlTest
@Tag("ocelot-pack")
@Tag("ocelot-instance")
class LuaFboTest {
  @ParameterizedTest(name = "{0} {1}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Scenes#fullCases"))
  @Timeout(120)
  def aSceneGivesLuaTheFbosBytes(arch: Arch, scene: Scene, gl: GlThread): Unit = {
    Brain.init()
    GlLeg.record(gl)
    val replay = Scenes.replay(scene)
    val source = scene.source
    val cap = Cadence.readbackCapTicks
    val host = new GlRigHost(gl, s"LuaFboTest $arch scene ${scene.id}")
    val spec = RigSpec(s"LuaFboTest-$arch-${scene.id}", arch, "scene.lua", tier = scene.tier, host = host.capture,
      pump = () => host.pump(), closeHost = () => host.close(), hostRenderer = Some(gl.info().renderer),
      params = Map("await" -> "park"), blobs = scene.blobs, cases = i => if (i == 1) Some(source) else None)
    Rig.using(spec) { rig =>
      val key = rig.gpu.device.key()
      val surfaces = mutable.Map.empty[Int, Array[Byte]]
      val latencies = mutable.ArrayBuffer.empty[Int]
      (1 to scene.awaits).foreach { k =>
        rig.parked(k)
        val frame = rig.sink.all("await").find(_.long(0) == k)
          .getOrElse(fail(s"$scene: no await record for await $k; ${rig.context}")).long(1)
        val epoch = rig.gpu.device.epoch()
        rig.holdPumps(false)
        val arrival = rig.until(s"$scene: frame $frame's readback within $cap ticks", cap)(
          host.capture.arrived(key, epoch, frame))
        latencies += arrival.ticks
        surfaces(k) = host.glHost.surfacePixels(key)
        rig.release()
      }
      rig.awaitFinish()
      Option(GlLeg.checkReadbackFinish(gl, host.glHost)).foreach(m => fail(s"readback glFinish: $m"))
      assertNull(rig.machine.lastError, "lastError")
      assertEquals(replay.handles, rig.sink.all("created").map(r => r.str(0) -> r.long(1)), "the handles Lua got")
      val delivered = host.capture.readbacks(key)
      scene.readAwaits.foreach { case (tag, await) =>
        val (expected, frame) = replay.reads(tag)
        val (bytes, frames) = rig.sink.rows(tag)
        assertTrue(frames.forall(_ == frame), s"$scene $tag: chunk frame ids ${frames.mkString(", ")}, not $frame")
        val readback = delivered.filter(_.frameId == frame).lastOption.getOrElse(
          fail(s"$scene $tag: the GL host delivered no readback of frame $frame: ${delivered.mkString(", ")}"))
        Bytes.difference(readback.pixels, bytes).foreach(d => fail(s"$scene $tag against the FBO's readback: $d"))
        assertTrue(await > 0, s"$scene $tag: no Await before the read")
        Bytes.difference(surfaces(await), bytes).foreach(d => fail(s"$scene $tag against a second read of the FBO: $d"))
        Bytes.difference(expected, bytes).foreach(d => fail(s"$scene $tag against the oracle's replay: $d"))
        assertTrue(rig.gpu.statuses.events().contains(s"done $frame"),
          s"$scene $tag: no done for frame $frame: ${rig.gpu.statuses.events()}")
      }
      assertEquals(Seq.empty, host.failureList, "the shadow oracle's and the hygiene checks' failures")
      Report.line(s"LuaFboTest $arch scene $scene: ${scene.reads.size} reads equal the FBO's readbacks, a second " +
        s"read of the FBO and the oracle's replay, frames ${scene.reads.map(replay.reads(_)._2).mkString(", ")}; " +
        s"readbacks within ${latencies.mkString(", ")} ticks (cap $cap)")
    }
  }
}
