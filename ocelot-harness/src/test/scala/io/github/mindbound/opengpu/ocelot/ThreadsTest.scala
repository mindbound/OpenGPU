package io.github.mindbound.opengpu.ocelot

import java.util.concurrent.CountDownLatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.{Tag, Test, Timeout}

/**
  * The two checks ShutdownTest makes, on planted threads (S10 plan P10.4, P14.1; amendments I9; N5): a non-daemon
  * thread started after the snapshot is reported, and a thread with the harness's prefix is reported by name though it
  * is a daemon; each stops being reported once it has ended, and a daemon thread with another name is never reported.
  */
@Tag("ocelot-unit")
class ThreadsTest {
  @Test
  @Timeout(30)
  def aNonDaemonThreadStartedAfterTheSnapshotIsReported(): Unit = {
    val snapshot = Threads.live()
    val (thread, release) = planted("planted-non-daemon", daemon = false)
    try assertEquals(Seq("planted-non-daemon"), plantedIn(Threads.leakedNonDaemon(snapshot)))
    finally {
      release.countDown()
      thread.join(10000)
    }
    assertEquals(Seq.empty, plantedIn(Threads.leakedNonDaemon(snapshot)))
  }

  @Test
  @Timeout(30)
  def aHarnessThreadIsReportedByNameDaemonOrNot(): Unit = {
    val (harness, releaseHarness) = planted(s"${Threads.HarnessPrefix}fold-planted", daemon = true)
    val (other, releaseOther) = planted("planted-daemon", daemon = true)
    try {
      assertEquals(Seq(s"${Threads.HarnessPrefix}fold-planted"), plantedFold(Threads.harnessAlive(100)))
      assertEquals(Seq.empty, plantedIn(Threads.leakedNonDaemon(Set.empty)))
    } finally {
      releaseHarness.countDown()
      releaseOther.countDown()
      harness.join(10000)
      other.join(10000)
    }
    assertEquals(Seq.empty, plantedFold(Threads.harnessAlive(100)))
  }

  /** This test's threads among those reported: JUnit's and Gradle's own may come and go meanwhile. */
  private def plantedIn(threads: Seq[Thread]): Seq[String] = threads.map(_.getName).filter(_.startsWith("planted"))

  /** The planted harness thread among those reported, so a rig's leak shows in ShutdownTest rather than here. */
  private def plantedFold(threads: Seq[Thread]): Seq[String] = threads.map(_.getName).filter(_.endsWith("fold-planted"))

  /** A started thread that waits on its latch. */
  private def planted(name: String, daemon: Boolean): (Thread, CountDownLatch) = {
    val release = new CountDownLatch(1)
    val t = new Thread(() => release.await(), name)
    t.setDaemon(daemon)
    t.start()
    (t, release)
  }
}
