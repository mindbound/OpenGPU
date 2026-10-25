package io.github.mindbound.opengpu.ocelot

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
  * One test's wall-clock deadline, shared by every wait the test makes and set below its `@Timeout`, so a slow test
  * ends in a wait's typed outcome before JUnit abandons its thread (S10 amendments I18).
  */
final class Deadline private (val atNanos: Long, val millis: Long) {
  def remainingMillis: Long = math.max(0L, (atNanos - System.nanoTime()) / 1000000L)

  override def toString: String = s"Deadline[$millis ms, $remainingMillis ms left]"
}

object Deadline {
  /** Below the ocelot tests' `@Timeout(120)`. */
  val TestMillis: Long = 90000L

  def afterMillis(ms: Long): Deadline = new Deadline(System.nanoTime() + ms * 1000000L, ms)

  def forTest(): Deadline = afterMillis(TestMillis)
}

/** The harness's cadence: the game's tick (19 §2) and the host frames pumped in each (23:182; S10 amendments F10). */
object Cadence {
  val TickMillis: Long = 50L

  /** Host frames per 50 ms tick, `opengpu.ocelot.pumpsPerTick`: 3 by default, 23:182's 60 Hz at 20 ticks per second. */
  lazy val pumpsPerTick: Int = {
    val value = Integer.getInteger("opengpu.ocelot.pumpsPerTick", 3)
    require(value >= 1, s"opengpu.ocelot.pumpsPerTick is $value; it must be at least 1")
    value
  }

  /**
    * 23:188's "readback within N ticks" in host frames: the plan's N = 10 ticks at one pump per tick [proposal; S10
    * plan P10.2, whose first CI figure is recorded]. LuaFboTest gates on it.
    */
  val ReadbackCapFrames: Int = 10

  /** N in ticks at this cadence, [[ReadbackCapFrames]] over [[pumpsPerTick]], rounded up: 4 at the default. */
  def readbackCapTicks: Int = (ReadbackCapFrames + pumpsPerTick - 1) / pumpsPerTick
}

/**
  * Runs `tick` at the game's period, until a condition holds or a cap is hit (05:110, :115). The condition is checked
  * before every tick, so a satisfied wait reports the ticks it took, and every wait has both a tick cap and a wall-time
  * cap, the latter never past the test's [[Deadline]]. Parking until the next tick's start is pacing that keeps the
  * tick at the game's period, never a wait for a condition, and no test sleeps or counts ticks blindly (S10 plan
  * P10.1).
  *
  * The schedule is a fixed rate that falls back to a fixed delay after an overrun (S10 amendments I3): a tick that
  * ends past the next one's slot pushes that start to its end plus [[Ticker.MinGapNanos]], so two late ticks never run
  * back to back. A catch-up burst would reset the call budget, count down `remainIdle` and advance `uptime` several
  * times with no wall time for the Lua executor and the fold thread between them (Machine.scala:489-498). Idle time
  * between two waits is no overrun: the next wait starts at the schedule's next slot, or at once when that has passed.
  *
  * A ticker stops when its thread is interrupted (S10 amendments I18), and [[release]] interrupts another thread that
  * is still using it, then waits for it to leave, so a teardown never disposes what a test thread is still ticking.
  * One thread uses it at a time.
  */
final class Ticker(tick: () => Unit, deadline: Deadline, periodNanos: Long = Ticker.PeriodNanos,
    minGapNanos: Long = Ticker.MinGapNanos) {
  private val count = new AtomicLong()
  private val lock = new Object
  /** The earliest start of the next tick. Read and written by the one thread using the ticker. */
  private var next: Long = System.nanoTime()
  @volatile private var runner: Thread = _
  @volatile private var cancelled = false
  @volatile private var closed = false

  /** Ticks this ticker has run. */
  def ticks: Long = count.get

  /**
    * Ticks until `condition` holds, under `maxTicks` and the smaller of `maxWallMillis` and what is left of `within`.
    *
    * @throws InterruptedException  when the thread is interrupted, or another thread releases the ticker
    * @throws IllegalStateException when the ticker is closed, or another thread is using it
    */
  def until(what: String, maxTicks: Int, maxWallMillis: Long = Ticker.WallMillis, within: Deadline = deadline)(
      condition: => Boolean): Outcome = using {
    val start = System.nanoTime()
    val capMillis = math.min(maxWallMillis, within.remainingMillis)
    val capNanos = capMillis * 1000000L
    var ticks = 0
    var late = 0
    var maxLate = 0L
    if (next < start) next = start
    var outcome: Outcome = null
    while (outcome == null) {
      if (condition) {
        outcome = Satisfied(what, ticks, millisSince(start), late, maxLate / 1000000L)
      } else if (ticks >= maxTicks) {
        outcome = TimedOut(what, "max_ticks", ticks, millisSince(start), late, maxLate / 1000000L, capMillis)
      } else if (System.nanoTime() - start >= capNanos) {
        outcome = TimedOut(what, "max_wall_time", ticks, millisSince(start), late, maxLate / 1000000L, capMillis)
      } else if (next - start > capNanos) {
        // The next tick would start past the cap: the condition's last check comes at the cap
        parkUntil(start + capNanos)
      } else {
        parkUntil(next)
        runTick()
        ticks += 1
        val lateness = schedule()
        if (lateness > 0) {
          late += 1
          maxLate = math.max(maxLate, lateness)
        }
      }
    }
    outcome
  }

  /** One tick at its slot, with no condition: I2's manual ticking (S10 amendments I2). */
  def step(): Unit = using {
    val now = System.nanoTime()
    if (next < now) next = now
    parkUntil(next)
    runTick()
    schedule()
  }

  /**
    * Waits without ticking until `condition` holds, under the smaller of `maxWallMillis` and what is left of `within`,
    * checking it every millisecond: for a state the Lua executor reaches between two ticks (S10 amendments I2).
    */
  def hold(what: String, maxWallMillis: Long = Ticker.WallMillis, within: Deadline = deadline)(
      condition: => Boolean): Outcome = using {
    val start = System.nanoTime()
    val capMillis = math.min(maxWallMillis, within.remainingMillis)
    var outcome: Outcome = null
    while (outcome == null) {
      if (condition) outcome = Satisfied(what, 0, millisSince(start), 0, 0)
      else if (System.nanoTime() - start >= capMillis * 1000000L) {
        outcome = TimedOut(what, "max_wall_time", 0, millisSince(start), 0, 0, capMillis)
      } else parkUntil(System.nanoTime() + Ticker.HoldPollNanos)
    }
    outcome
  }

  /**
    * Interrupts the thread using the ticker, if it is another one, and waits up to 10 s for it to leave.
    *
    * @return whether no other thread is using the ticker now
    */
  def release(): Boolean = {
    val other = runner
    if (other == null || other == Thread.currentThread()) true
    else {
      cancelled = true
      other.interrupt()
      val end = System.nanoTime() + Ticker.ReleaseMillis * 1000000L
      lock.synchronized {
        var left = end - System.nanoTime()
        while (runner != null && left > 0) {
          lock.wait(math.max(1L, left / 1000000L))
          left = end - System.nanoTime()
        }
      }
      cancelled = false
      runner == null
    }
  }

  /** [[release]], then refuses every later use. */
  def close(): Boolean = {
    val left = release()
    closed = true
    left
  }

  private def runTick(): Unit = {
    tick()
    count.incrementAndGet()
  }

  /** Moves the schedule past a tick that just ended; returns by how much the next start is late, or 0. */
  private def schedule(): Long = {
    val slot = next + periodNanos
    val earliest = System.nanoTime() + minGapNanos
    if (earliest > slot) {
      next = earliest
      earliest - slot
    } else {
      next = slot
      0L
    }
  }

  private def parkUntil(at: Long): Unit = {
    var left = at - System.nanoTime()
    while (left > 0) {
      LockSupport.parkNanos(this, left)
      checkCancelled()
      left = at - System.nanoTime()
    }
    checkCancelled()
  }

  private def checkCancelled(): Unit = {
    if (Thread.interrupted() || cancelled) {
      throw new InterruptedException("the ticker was interrupted")
    }
  }

  private def using[T](body: => T): T = {
    if (closed) throw new IllegalStateException("the ticker is closed")
    lock.synchronized {
      val other = runner
      if (other != null) throw new IllegalStateException(s"the ticker is in use by ${other.getName}")
      runner = Thread.currentThread()
    }
    try body
    finally lock.synchronized {
      runner = null
      lock.notifyAll()
    }
  }

  private def millisSince(start: Long): Long = (System.nanoTime() - start) / 1000000L
}

object Ticker {
  /** 19 §2's cadence: one `Workspace.update()` per 50 ms. */
  val PeriodNanos: Long = Cadence.TickMillis * 1000000L

  /** The least gap between a late tick's end and the next tick's start (S10 amendments I3). */
  val MinGapNanos: Long = 10L * 1000000L

  /** A wait's own wall-time cap (S10 plan P10.1). */
  val WallMillis: Long = 30000L

  val HoldPollNanos: Long = 1000000L

  val ReleaseMillis: Long = 10000L
}
