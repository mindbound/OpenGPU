package io.github.mindbound.opengpu.ocelot

import io.github.mindbound.opengpu.core.device.DeviceCaps
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig
import io.github.mindbound.opengpu.gltestkit.{GlTest, GlThread}
import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue, fail}
import org.junit.jupiter.api.{Tag, Timeout}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
  * "Submits and texture calls validate exactly as with a GL host" (28:204; 24:228; S10 plan P14.6) through real OC:
  * NoRendererTest (e)'s calls on a T2 device over GlTestHost, its three presents giving frame ids, each waited for on
  * both sides, give through Lua exactly what Java gives with the same inputs on a DeviceRig whose FakeRenderHost runs
  * the tier-E oracle and is stepped after each present. They reach the same stream error codes and texture-call
  * messages as under NoRenderHost, every readback the GL host delivered matched the tee's shadow oracle, and the GL
  * host's hygiene and ledger checks found nothing (GlRigHost). On the task's full set: 5.3 and 5.2 on ocelotGlTest,
  * 5.4 on ocelotInstanceTest (S10 decisions D4).
  */
@GlTest
@Tag("ocelot-pack")
@Tag("ocelot-instance")
class GlParityTest {
  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#fullSet"))
  @Timeout(120)
  def callsValidateAsWithoutARenderer(arch: Arch, gl: GlThread): Unit = {
    Brain.init()
    GlLeg.record(gl)
    val script = NoRendererTest.validation(rendering = true)
    val source = script.source
    val host = new GlRigHost(gl, s"GlParityTest $arch")
    val spec = RigSpec(s"GlParityTest-$arch", arch, "calls.lua", tier = 2, host = host.capture,
      pump = () => host.pump(), closeHost = () => host.close(), hostRenderer = Some(gl.info().renderer),
      cases = i => if (i == 1) Some(source) else None)
    Rig.using(spec) { rig =>
      rig.awaitFinish(maxTicks = 1000)
      val outcomes = CallReplay.replay(DeviceRig.of(DeviceCaps.forTier(2)), rig.sink, script.all, script.awaits)
      outcomes.find(!_.same).foreach(o => fail(s"$arch: $o"))
      outcomes.flatMap(_.missed).headOption.foreach(m => fail(s"$arch: $m"))
      val reached = NoRendererTest.assertCoverage(outcomes)
      val presents = outcomes.filter(_.call.name == "present").map(_.lua)
      assertEquals(Seq("1", "2", "3"), presents, "the presents' frame ids")
      val key = rig.gpu.device.key()
      val delivered = host.capture.readbacks(key)
      assertTrue(delivered.exists(_.frameId == 3), s"the GL host's readbacks: ${delivered.mkString(", ")}")
      val statuses = rig.gpu.statuses.events()
      Seq(1L, 2L, 3L).foreach(f => assertTrue(statuses.contains(s"done $f"), s"no done for frame $f: $statuses"))
      assertEquals(Seq.empty, host.failureList, "the shadow oracle's and the hygiene checks' failures")
      Report.line(s"GlParityTest $arch: ${outcomes.size} calls, $reached stream error codes and " +
        s"${NoRendererTest.TextureMessages.size} texture-call messages on GlTestHost as on the Java device, frames " +
        s"${presents.mkString(", ")} read back as ${delivered.map(_.frameId).mkString(", ")}")
    }
  }
}
