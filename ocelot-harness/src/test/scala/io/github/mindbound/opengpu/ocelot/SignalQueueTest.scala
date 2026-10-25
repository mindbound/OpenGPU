package io.github.mindbound.opengpu.ocelot

import java.util.concurrent.atomic.AtomicBoolean

import io.github.mindbound.opengpu.core.fixtures.device.ManualClock
import io.github.mindbound.opengpu.core.render.FakeRenderHost
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse}
import org.junit.jupiter.api.{Tag, Timeout}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

import scala.jdk.CollectionConverters._

/**
  * The queue test at the pack's 1 024 and the instance's 256 (19:202; 05:110), with the harness entity's own
  * `opengpu_frame` [proposal; S10 amendments F11, decisions D7], on `FakeRenderHost`, whose frames render only when the
  * ticker pumps it, and a ManualClock for the device's 1 s `"dropped"` (28:112). Through real OC it shows:
  *  - the signal's layout, `opengpu_frame(card, frameId, status)` (24:127): the entity's address as a String, the frame
  *    id as a Long (a Lua integer on 5.3 and 5.4), and `"dropped"`, then `"done"`, for the same frame (28:110-112);
  *  - that the queue takes exactly the profile's `maxSignalQueueSize` of the script's own signals, a figure that
  *    differs from brain's default 256 only on the pack (19 §3);
  *  - that a frame status arriving while the queue is full is refused and counted, never retried, and that no other
  *    signal fits after it (Machine.scala:307).
  */
@Tag("ocelot-pack")
@Tag("ocelot-instance")
class SignalQueueTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#task"))
  @Timeout(120)
  def frameSignalsAndTheQueueCap(arch: Arch): Unit = {
    val cap = Brain.init().profile.maxSignalQueueSize
    val fake = new FakeRenderHost()
    val clock = new ManualClock()
    val rendering = new AtomicBoolean(false)
    val spec = RigSpec(s"SignalQueueTest-${arch.id}", arch, "signal_queue.lua", host = fake,
      pump = () => if (rendering.get) fake.step(), nanoClock = clock)
    Rig.using(spec) { rig =>
      rig.until("frame 1 presented")(rig.sink.count("presented") >= 1)
      clock.advanceMillis(1100)
      rig.until("the dropped signal")(rig.sink.count("signal") >= 1)
      rendering.set(true)
      rig.until("the done signal")(rig.sink.count("signal") >= 2)
      rig.awaitFinish()

      val sink = rig.sink
      val caps = sink.first("caps")
      assertEquals(Seq("host-gl", "3", "640", "400", "rgb565"),
        Seq(caps.str(0), caps.long(1).toString, caps.long(2).toString, caps.long(3).toString, caps.str(4)), s"$caps")
      assertEquals(0L, sink.first("drained").long(0), "signals queued before the fill")
      val Seq(f1, f2) = sink.all("presented").map(_.long(0))
      val integer = if (arch.id == "lua53" || arch.id == "lua54") "integer" else "none"
      Seq("dropped", "done").zip(sink.all("signal")).foreach { case (status, s) =>
        assertEquals(Seq("opengpu_frame", rig.gpu.node.address, f1.toString, status, integer),
          Seq(s.str(0), s.str(1), s.long(2).toString, s.str(3), s.str(4)), s"$status: $s")
        // OC's converter keeps a Long a Long (Machine.scala:285-301), so 5.3 and 5.4 see an integer, which comes back
        // through put as a Long (ExtendedLuaState.scala:101)
        if (integer == "integer") assertEquals(classOf[java.lang.Long], s.args(2).getClass, s"$status: $s")
      }
      assertEquals(cap.toLong, sink.first("filled").long(0), s"the queue's cap under ${Brain.init().profile.name}")
      assertEquals(f2, sink.first("confirmed").long(0))
      assertFalse(sink.first("pushAfter").bool(0), "a signal after the refused frame status")
      assertEquals(Seq(s"sent dropped $f1", s"sent done $f1", s"refused done $f2"), rig.gpu.frameSignals)
      assertEquals(Seq(s"dropped $f1", s"done $f1", s"done $f2").asJava, rig.gpu.statuses.events())
      val idType = sink.first("signal").args(2).getClass.getSimpleName
      Report.line(s"SignalQueueTest ${arch.id}: cap $cap, frames $f1 and $f2, " +
        s"${rig.gpu.frameSignals.mkString(", ")}, frame id back as $idType, " +
        s"${sink.first("confirmed").long(1)} ticks to confirm frame $f2")
    }
  }
}
