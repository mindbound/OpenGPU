package io.github.mindbound.opengpu.ocelot

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import io.github.mindbound.opengpu.core.device.{Charges => DeviceCharges}
import io.github.mindbound.opengpu.core.font.GlyphTables
import io.github.mindbound.opengpu.core.fixtures.device.OracleRecordRenderer
import io.github.mindbound.opengpu.core.render.FakeRenderHost
import org.junit.jupiter.api.Assertions.{assertArrayEquals, assertEquals, assertFalse, assertTrue}
import org.junit.jupiter.api.{Tag, Timeout}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
  * The call budget through real OC (05:134's "call-budget behaviour under computer.callBudgets"; 23:159's budgets and
  * LimitReached retry idempotence; 28 §8.2; S10 plan P14.7 [proposal for its exact cases]; amendments F12, I2), on
  * every arch of the task, with the ticks run by hand: brain refills the budget at every `update()` (Machine.scala:497-
  * 498), and the Lua executor makes direct calls between ticks, so only a test that runs each tick itself knows how
  * much budget a call meets. Each case waits in harness.lua's `park(1)` until the first tick by hand, which refills
  * the budget and lets it go; after that one tick runs per synchronized retry. The expected charges come from
  * `:core`'s charge table and brain's rules: a direct call over what is left throws LimitReachedException and is retried
  * as a synchronized call at the next tick, charged nothing (Machine.scala:253-261, :530-537); the retry's tick has
  * refilled the budget for the calls after it.
  *  - (a) readAll bursts of T3's 8 calls (7 of 51 rows, one of 43): two on the pack's settings (1.5 per tick, the pack
  *    legs and OC-LuaJIT's), which meet the budget twice, and one on the instance (4 per tick), which never does (S10
  *    amendments I2's margin). The bytes are the same in every burst and are the confirmed frame's.
  *  - (b) createTexture(1024, 1024) on T3: c = 8 > k = 1.5, so Device's first attempt throws its own LimitReached,
  *    which the entity turns into OC's, and the retry pauses (8 - 1.5 - 1.5) / 1.5 / 20 s, which brain counts in whole
  *    ticks, then returns the handle (28:713, :717; Machine.scala:221-242).
  */
@Tag("ocelot-pack")
@Tag("ocelot-instance")
class ChargesTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#task"))
  @Timeout(120)
  def readbackBurstsMeetTheBudgetAsTheTableSays(arch: Arch): Unit = {
    val profile = Brain.init().profile
    // The T3 CPU's budget (CPU.scala's callBudget for Tier.Three); the CPU is the case's one budget component
    val k = profile.callBudgets(2)
    val reads = if (profile.name == Profiles.Instance.name) 1 else 2
    val fake = new FakeRenderHost(new OracleRecordRenderer(GlyphTables.font0()))
    val spec = RigSpec(s"ChargesTest-a-$arch", arch, "charges.lua", host = fake, pump = () => fake.step(),
      params = Map("case" -> "readAll", "reads" -> reads))
    Rig.using(spec) { rig =>
      parkAndRelease(rig)
      val frame = rig.sink.first("ready").long(0)
      val costs = Seq.fill(reads)(ChargesTest.readAllCosts).flatten
      val (expected, limits) = ChargesTest.model(costs, k)
      rig.step()
      var steps = 1
      var seen = 0
      while (rig.sink.count("burstDone") == 0) {
        rig.hold(s"tick $steps's slice to stop at a limit or at the burst's end")(
          (rig.gpu.charges.limits > seen || rig.sink.count("burstDone") > 0) && !rig.machine.isExecuting)
        if (rig.sink.count("burstDone") == 0) {
          assertTrue(steps <= limits, s"tick ${steps + 1} by hand, past the model's $limits limits: " +
            s"${rig.gpu.charges.snapshot}")
          seen = rig.gpu.charges.limits
          rig.step()
          steps += 1
        }
      }
      assertEquals(expected, rig.gpu.charges.snapshot, s"charges under ${profile.name} at $k per tick")
      assertEquals(1 + limits, steps, "ticks by hand: the first, then one per synchronized retry")
      rig.awaitFinish()
      val screen = rig.gpu.device.snapshot()
      assertEquals(frame, screen.confirmedFrameId, "the confirmed frame")
      (1 to reads).foreach { n =>
        val (bytes, frames) = rig.sink.rows(s"burst$n")
        assertEquals(Seq.fill(8)(frame), frames, s"burst $n's frame ids")
        Bytes.difference(screen.confirmedPixels(), bytes).foreach(d => throw new AssertionError(s"burst $n: $d"))
      }
      assertArrayEquals(fake.readbacks(rig.gpu.device.key()).asScala.last.pixels,
        screen.confirmedPixels(), "the host's readback")
      Report.line(s"ChargesTest $arch (${profile.name}, $k per tick): $reads T3 readAll bursts, ${costs.size} calls, " +
        s"$limits LimitReached, $steps ticks by hand, the same bytes")
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#task"))
  @Timeout(120)
  def aTwoPhaseCreatePausesOnceAndReturnsTheHandle(arch: Arch): Unit = {
    val cost = DeviceCharges.bytes(1024L * 1024 * 2)
    val pause = DeviceCharges.pauseSeconds(3, cost)
    assertTrue(DeviceCharges.twoPhase(3, cost), s"c = $cost against T3's credit")
    // Machine.pause counts whole ticks (Machine.scala:222)
    val pauseTicks = (pause * 20).toInt
    val spec = RigSpec(s"ChargesTest-b-$arch", arch, "charges.lua", params = Map("case" -> "createTexture"))
    Rig.using(spec) { rig =>
      parkAndRelease(rig)
      rig.step()
      rig.hold("the first attempt to stop at Device's LimitReached")(
        rig.gpu.charges.snapshot.contains(Charges.TwoPhase) && !rig.machine.isExecuting)
      assertEquals(Seq(Charges.TwoPhase), rig.gpu.charges.snapshot, "the first attempt charges nothing")
      // The retry runs inside this tick's update(), on this thread
      rig.step()
      assertEquals(Seq(Charges.TwoPhase, Charges.pause(pause)), rig.gpu.charges.snapshot, "the retry's pause")
      (1 to pauseTicks).foreach { t =>
        assertTrue(rig.machine.isPaused, s"paused before tick $t of $pauseTicks")
        assertEquals(0, rig.sink.count("created"), s"a result during the pause's tick $t")
        rig.step()
      }
      assertFalse(rig.machine.isPaused, "paused after the pause's ticks")
      assertEquals(0, rig.sink.count("created"), "a result before the tick that resumes the machine")
      rig.step()
      rig.hold("the handle")(rig.sink.count("created") > 0 && !rig.machine.isExecuting)
      val r = rig.sink.first("created")
      assertTrue(!r.isNil(0) && r.long(0) > 0 && r.isNil(1), s"createTexture(1024, 1024); $r")
      assertEquals(Seq(Charges.TwoPhase, Charges.pause(pause)), rig.gpu.charges.snapshot, "charges in all")
      rig.awaitFinish()
      Report.line(s"ChargesTest $arch: createTexture(1024, 1024) at c = $cost: one two-phase LimitReached, one pause " +
        s"of ${Charges.pause(pause).stripPrefix("pause ")} s over $pauseTicks ticks, then the handle")
    }
  }

  /**
    * Waits for the script to park, clears the charges so far and releases it, so the next tick by hand lets it go on
    * the budget that tick refills.
    */
  private def parkAndRelease(rig: Rig): Unit = {
    rig.parked(1)
    rig.gpu.charges.clear()
    rig.release()
  }
}

object ChargesTest {
  /** A T3 readAll's charges: 7 calls of 51 rows and one of 43, at 640 x 2 B per row (28:690; harness.lua's readAll). */
  def readAllCosts: Seq[Double] = {
    val row = 640 * 2
    val rows = 65536 / row
    val sizes = Seq.fill(400 / rows)(rows) :+ (400 % rows)
    sizes.map(n => DeviceCharges.bytes(n.toLong * row))
  }

  /**
    * The charges the costs meet in one burst at `k` per tick, from a full budget: `consume c` while c fits what is
    * left, else `limit c`, then the synchronized retry's `consume c`, which charges nothing, and a full budget again.
    * Every decision must clear by more than the 1/2^31 that each direct call also takes (Machine.scala:349-351),
    * which the model leaves out.
    *
    * @return the events and the number of limits
    */
  def model(costs: Seq[Double], k: Double): (Seq[String], Int) = {
    val events = mutable.ArrayBuffer.empty[String]
    var left = k
    var limits = 0
    costs.foreach { c =>
      assertTrue(math.abs(c - left) > 1e-6, s"a charge of $c too close to the $left left")
      if (c > left) {
        events += Charges.limit(c)
        events += Charges.consume(c)
        limits += 1
        left = k
      } else {
        events += Charges.consume(c)
        left -= c
      }
    }
    (events.toList, limits)
  }
}
