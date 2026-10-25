package io.github.mindbound.opengpu.ocelot

import scala.jdk.CollectionConverters._

/**
  * 05:117's thread rule as S10 applies it (S10 amendments F6, I9; decisions D7): every thread the harness starts is
  * named `opengpu-ocelot-*` and must have ended after teardown, daemon or not, since all of them are daemons and a
  * check of non-daemon threads alone could never fail for one; and no non-daemon thread outside the snapshot
  * Brain.init takes may be left. `opengpu-gl`, which `:gl-testkit`'s GlContextExtension keeps for the whole JVM and
  * closes after the last class, and brain's `Ocelot-*` pools are allowed and logged.
  */
object Threads {
  /** The prefix of every thread the harness starts: the fold executors' `opengpu-ocelot-fold-<deviceId>` (P7.5). */
  val HarnessPrefix: String = "opengpu-ocelot-"

  /** Threads that may outlive the harness: `:gl-testkit`'s GL thread and brain's pools. */
  def allowed(t: Thread): Boolean = t.getName == "opengpu-gl" || t.getName.startsWith("Ocelot-")

  /** The live threads now. */
  def live(): Set[Thread] = Thread.getAllStackTraces.keySet.asScala.filter(_.isAlive).toSet

  /** Live threads the harness started, daemon or not, after waiting up to `graceMillis` for each to end. */
  def harnessAlive(graceMillis: Long): Seq[Thread] = {
    val end = System.nanoTime() + graceMillis * 1000000L
    live().filter(_.getName.startsWith(HarnessPrefix)).toSeq.sortBy(_.getName).filter { t =>
      t.join(math.max(1L, (end - System.nanoTime()) / 1000000L))
      t.isAlive
    }
  }

  /** Live non-daemon threads that are not in `snapshot`, not the calling thread and not [[allowed]]. */
  def leakedNonDaemon(snapshot: Set[Thread]): Seq[Thread] =
    live().filter(t => !t.isDaemon && !snapshot.contains(t) && t != Thread.currentThread() && !allowed(t)).toSeq
      .sortBy(_.getName)

  /** "name (daemon)" or "name (non-daemon)", for a report line. */
  def describe(threads: Iterable[Thread]): String =
    if (threads.isEmpty) "none"
    else threads.toSeq.sortBy(_.getName).map(t => s"${t.getName} (${if (t.isDaemon) "daemon" else "non-daemon"})")
      .mkString(", ")
}
