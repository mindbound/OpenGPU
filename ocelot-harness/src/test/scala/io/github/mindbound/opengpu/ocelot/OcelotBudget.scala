package io.github.mindbound.opengpu.ocelot

import java.util.Locale

import scala.collection.mutable

import io.github.mindbound.opengpu.core.device.CallBudget
import totoro.ocelot.brain.entity.machine.{Context, LimitReachedException}

/**
  * `:core`'s CallBudget on ocelot's Context (28:1077; H4), as the root's `@Callback` layer forwards to OC's:
  * `consume` is `consumeCallBudget`, whose LimitReachedException propagates untouched so OC retries the call
  * synchronized on the next tick (Machine.scala:253-261), and `pause` is `Context.pause`. Each call is also recorded
  * in `charges`, an OpenGPU-side observation that holds in-game too, unlike ocelot's `getRemainingCallBudget`
  * (19:202), for the charge tests (S10 plan P7.3, P14.7).
  */
final class OcelotBudget(context: Context, charges: Charges) extends CallBudget {
  override def consume(cost: Double): Unit = {
    try context.consumeCallBudget(cost)
    catch {
      case e: LimitReachedException =>
        charges.limit(cost)
        throw e
    }
    charges.consumed(cost)
  }

  override def pause(seconds: Double): Unit = {
    charges.paused(seconds)
    context.pause(seconds)
  }
}

/**
  * One device's charges through OC, in call order: `consume <cost>`, `limit <cost>` for a consume OC refused,
  * `two-phase` for Device's own LimitReached at `bitblt`'s first attempt (28:712), which charges nothing, and
  * `pause <seconds>`, each with the cost or time to nine places, as `:core`'s RecordingBudget formats them.
  * Thread-safe: direct calls run on brain's executor threads and synchronized retries on the ticking thread.
  */
final class Charges {
  private val events = mutable.ArrayBuffer.empty[String]

  def consumed(cost: Double): Unit = add(Charges.consume(cost))

  def limit(cost: Double): Unit = add(Charges.limit(cost))

  def twoPhase(): Unit = add(Charges.TwoPhase)

  def paused(seconds: Double): Unit = add(Charges.pause(seconds))

  def snapshot: Seq[String] = synchronized(events.toList)

  def limits: Int = synchronized(events.count(_.startsWith("limit ")))

  def pauses: Seq[Double] = synchronized(events.collect {
    case e if e.startsWith("pause ") => e.stripPrefix("pause ").toDouble
  }.toList)

  def clear(): Unit = synchronized(events.clear())

  private def add(event: String): Unit = synchronized(events += event)
}

/** The events' text, for a test's expected list. */
object Charges {
  val TwoPhase: String = "two-phase"

  def consume(cost: Double): String = "consume %.9f".formatLocal(Locale.ROOT, cost)

  def limit(cost: Double): String = "limit %.9f".formatLocal(Locale.ROOT, cost)

  def pause(seconds: Double): String = "pause %.9f".formatLocal(Locale.ROOT, seconds)
}
