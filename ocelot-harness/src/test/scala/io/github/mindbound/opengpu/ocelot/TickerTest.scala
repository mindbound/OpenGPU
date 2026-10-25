package io.github.mindbound.opengpu.ocelot

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}

import scala.collection.mutable

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue, fail}
import org.junit.jupiter.api.{Tag, Test, Timeout}

/**
  * The Ticker's bounded waits on a recording tick (05:110, :115; S10 plan P10.1, P14.1; amendments I2, I3, I18): the
  * condition is checked before every tick, both caps end a wait with its typed outcome, ticks keep the 50 ms period,
  * an overrun is followed by a fixed delay rather than a burst, a test's deadline caps every wait, and an interrupt or
  * a release from another thread stops the ticking.
  */
@Tag("ocelot-unit")
class TickerTest {
  /** Each tick's start and end, in ns; `work` runs inside the tick with its index. */
  private final class Recorder(work: Int => Unit = _ => ()) {
    val starts: mutable.ArrayBuffer[Long] = mutable.ArrayBuffer.empty[Long]
    val ends: mutable.ArrayBuffer[Long] = mutable.ArrayBuffer.empty[Long]

    def tick(): Unit = {
      starts += System.nanoTime()
      work(starts.size)
      ends += System.nanoTime()
    }
  }

  private def ticker(r: Recorder, deadline: Deadline = Deadline.afterMillis(20000)): Ticker =
    new Ticker(() => r.tick(), deadline)

  private def ms(nanos: Long): Double = nanos / 1e6

  @Test
  @Timeout(30)
  def aConditionThatHoldsAtOnceTakesNoTick(): Unit = {
    val r = new Recorder
    val outcome = ticker(r).until("now", 10)(true)
    assertTrue(outcome.isInstanceOf[Satisfied], s"$outcome")
    assertEquals(0, outcome.ticks, s"$outcome")
    assertEquals(0, r.starts.size, "ticks run")
  }

  @Test
  @Timeout(30)
  def aConditionIsSatisfiedAfterTheTicksItTook(): Unit = {
    val r = new Recorder
    val outcome = ticker(r).until("three ticks", 10)(r.starts.size >= 3)
    assertTrue(outcome.isInstanceOf[Satisfied], s"$outcome")
    assertEquals(3, outcome.ticks)
    assertEquals(3, r.starts.size)
  }

  @Test
  @Timeout(30)
  def theTickCapEndsAWaitWithMaxTicks(): Unit = {
    val r = new Recorder
    ticker(r).until("never", 4)(false) match {
      case TimedOut(_, "max_ticks", 4, _, _, _, _) =>
      case other => fail(s"$other")
    }
    assertEquals(4, r.starts.size)
  }

  @Test
  @Timeout(30)
  def theWallCapEndsAWaitWithMaxWallTimeAfterASlowTick(): Unit = {
    val r = new Recorder(_ => Thread.sleep(120))
    val outcome = ticker(r).until("never", 1000, maxWallMillis = 300)(false)
    outcome match {
      case TimedOut(_, "max_wall_time", ticks, millis, _, _, 300L) =>
        assertTrue(ticks >= 1 && ticks <= 3, s"$outcome")
        assertTrue(millis >= 300 && millis < 2000, s"$outcome")
      case other => fail(s"$other")
    }
  }

  @Test
  @Timeout(30)
  def ticksKeepTheGamesPeriod(): Unit = {
    val r = new Recorder
    val t = ticker(r)
    val before = System.nanoTime()
    t.until("six ticks", 10)(r.starts.size >= 6)
    // A fixed rate: the first slot is the wait's start, so tick k starts k periods or more after `before`, whatever one
    // wake-up's jitter was. The upper bound only shows that a period was kept at all, on a loaded runner too
    val offsets = r.starts.map(s => ms(s - before)).toList
    assertTrue(offsets.zipWithIndex.forall { case (o, k) => o >= k * 50.0 }, s"tick starts after the wait's: $offsets")
    assertTrue(offsets.last < 5 * 50.0 + 250, s"tick starts after the wait's, ms: $offsets")
  }

  @Test
  @Timeout(30)
  def anOverrunIsFollowedByAFixedDelayNotABurst(): Unit = {
    val r = new Recorder(i => if (i == 3) Thread.sleep(300))
    val outcome = ticker(r).until("eight ticks", 20)(r.starts.size >= 8)
    // The 300 ms tick ends about 250 ms past the next slot; a catch-up loop would then run five ticks back to back
    val idle = r.starts.drop(1).zip(r.ends).map { case (start, previousEnd) => ms(start - previousEnd) }.toList
    assertTrue(idle.forall(_ >= ms(Ticker.MinGapNanos)), s"idle time before each tick after the first, ms: $idle")
    // A loaded runner may make another tick late too; the slow one is late by its 250 ms
    assertTrue(outcome.lateTicks >= 1, s"$outcome")
    assertTrue(outcome.maxLateMillis >= 200, s"$outcome")
    // From the late tick on, the rate resumes from its start, the slow tick's end plus the gap
    val resumed = r.ends(2) + Ticker.MinGapNanos
    val after = r.starts.drop(3).map(s => ms(s - resumed)).toList
    assertTrue(after.zipWithIndex.forall { case (o, k) => o >= k * 50.0 }, s"tick starts after resuming, ms: $after")
  }

  @Test
  @Timeout(30)
  def theTestsDeadlineCapsEveryWait(): Unit = {
    val r = new Recorder
    val t = ticker(r, Deadline.afterMillis(400))
    assertTrue(t.until("first", 1000, maxWallMillis = 250)(false).isInstanceOf[TimedOut])
    t.until("second", 1000, maxWallMillis = 30000)(false) match {
      case TimedOut(_, "max_wall_time", _, millis, _, _, cap) =>
        assertTrue(cap <= 150, s"the second wait's cap, $cap ms, is not what was left of the deadline")
        assertTrue(millis <= 1000, s"the second wait took $millis ms")
      case other => fail(s"$other")
    }
  }

  @Test
  @Timeout(30)
  def holdWaitsWithoutTicking(): Unit = {
    val r = new Recorder
    val t = ticker(r)
    val flag = new AtomicBoolean()
    val setter = new Thread(() => {
      Thread.sleep(100)
      flag.set(true)
    }, "ticker-test-setter")
    setter.start()
    val held = t.hold("the flag", 5000)(flag.get)
    setter.join(5000)
    assertTrue(held.isInstanceOf[Satisfied], s"$held")
    assertTrue(t.hold("never", 50)(false).isInstanceOf[TimedOut])
    t.step()
    assertEquals(1, r.starts.size, "ticks run")
  }

  @Test
  @Timeout(30)
  def anInterruptOrAReleaseStopsTheTicking(): Unit = {
    def run(stop: (Thread, Ticker) => Unit): Throwable = {
      val r = new Recorder
      val t = ticker(r)
      val started = new CountDownLatch(1)
      val thrown = new AtomicReference[Throwable]()
      val worker = new Thread(() => {
        try t.until("never", 100000, maxWallMillis = 20000) {
          started.countDown()
          false
        } catch {
          case e: Throwable => thrown.set(e)
        }
      }, "ticker-test-worker")
      worker.start()
      assertTrue(started.await(5, TimeUnit.SECONDS))
      stop(worker, t)
      worker.join(5000)
      assertTrue(!worker.isAlive, "the ticking thread did not stop")
      thrown.get
    }
    val interrupted = run((worker, _) => worker.interrupt())
    assertTrue(interrupted.isInstanceOf[InterruptedException], s"$interrupted")
    val released = new AtomicBoolean()
    val byRelease = run((_, t) => released.set(t.release()))
    assertTrue(byRelease.isInstanceOf[InterruptedException], s"$byRelease")
    assertTrue(released.get, "release() returned before the ticking thread left")
  }
}
