package io.github.mindbound.opengpu.ocelot

import io.github.mindbound.opengpu.gltestkit.{GlTest, GlThread}
import org.junit.jupiter.api.Assertions.{assertEquals, fail}
import org.junit.jupiter.api.{Tag, Timeout}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
  * The self-test program on real GL through real OC (S13 plan P6.3): GlRigHost, llvmpipe on CI, with free-running
  * pumps, as GlParityTest, since the program polls. On the task's full set: 5.3 and 5.2 on ocelotGlTest, 5.4 on
  * ocelotInstanceTest (S10 decisions D4), and OC-LuaJIT on the local leg. SelfTest.check holds the FBO's readback of
  * the program's frame, as the GL host delivered it, to the program's printed digests and its EXPECT block, which
  * SelfTestOracleTest holds to the oracle, so GL = EXPECT = oracle; the card's line names the GL thread's renderer as
  * hostRenderer. Also: the readbacks' glFinish policy is the one the leg asked for, and the tee's shadow oracle, the
  * hygiene checks and the ledger found nothing (GlRigHost).
  */
@GlTest
@Tag("ocelot-pack")
@Tag("ocelot-instance")
class SelfTestGlTest {
  @ParameterizedTest(name = "{0} T{1}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.SelfTest#fullCases"))
  @Timeout(120)
  def theProgramPassesOnGl(arch: Arch, tier: Int, gl: GlThread): Unit = {
    Brain.init()
    GlLeg.record(gl)
    val host = new GlRigHost(gl, s"SelfTestGlTest $arch T$tier")
    val renderer = gl.info().renderer
    val spec = SelfTest.spec(s"SelfTestGlTest-$arch-T$tier", arch, tier, host.capture, () => host.pump(),
      () => host.close(), Some(renderer))
    Rig.using(spec) { rig =>
      rig.awaitFinish(maxTicks = 1200)
      Option(GlLeg.checkReadbackFinish(gl, host.glHost)).foreach(m => fail(s"readback glFinish: $m"))
      val key = rig.gpu.device.key()
      val delivered = host.capture.readbacks(key)
      val pixels = SelfTest.check(rig, tier, f => delivered.filter(_.frameId == f).lastOption.map(_.pixels),
        Some(renderer))
      assertEquals(Seq.empty, host.failureList, "the shadow oracle's and the hygiene checks' failures")
      Report.line(s"SelfTestGlTest $arch T$tier: frame ${pixels.frame}'s ${pixels.digests.size} chunks from the FBO " +
        s"are the program's EXPECT and what it printed (${pixels.dropped} dropped noted): " +
        SelfTest.printed(rig).mkString(" | "))
    }
  }
}
