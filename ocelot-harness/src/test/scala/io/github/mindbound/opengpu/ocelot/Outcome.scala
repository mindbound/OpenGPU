package io.github.mindbound.opengpu.ocelot

import org.junit.jupiter.api.Assertions.fail

/**
  * A bounded wait's typed outcome (05:115): satisfied, or timed out naming the cap it hit, "max_ticks" or
  * "max_wall_time" (RH's `ConditionTimedOut(limit)`). Both carry the ticks and the wall time the wait took, and how
  * many of its ticks started late after an overrun and the latest by how much (S10 amendments I3), so a slow runner
  * reads as such in the failure.
  */
sealed trait Outcome {
  def what: String
  def ticks: Int
  def millis: Long
  def lateTicks: Int
  def maxLateMillis: Long

  /** This, when satisfied; otherwise fails the test with the cap, the counts and `context` (the rig's state). */
  def orFail(context: => String): Satisfied
}

final case class Satisfied(what: String, ticks: Int, millis: Long, lateTicks: Int, maxLateMillis: Long)
  extends Outcome {
  override def orFail(context: => String): Satisfied = this
}

/**
  * @param capMillis the wall-time cap the wait ran under: its own, or what was left of the test's deadline when that
  *                  was less (S10 amendments I18)
  */
final case class TimedOut(what: String, limit: String, ticks: Int, millis: Long, lateTicks: Int, maxLateMillis: Long,
    capMillis: Long) extends Outcome {
  override def orFail(context: => String): Satisfied = {
    val detail = context
    fail(s"$what: timed out on $limit after $ticks ticks and $millis ms (wall cap $capMillis ms; $lateTicks late " +
      s"ticks, the latest $maxLateMillis ms late)" + (if (detail.isEmpty) "" else s"; $detail"))
  }
}
