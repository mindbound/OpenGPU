package io.github.mindbound.opengpu.ocelot

import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertTrue}
import org.junit.jupiter.api.{Tag, Test, Timeout}
import totoro.ocelot.brain.event.{EventBus, MachineCrashEvent}

/**
  * The bounded event buffer (05:115; S10 plan P10.3, P14.1): drops past its capacity are counted, the oldest goes
  * first, and close cancels its EventBus subscription, so an event sent after close reaches it no more.
  */
@Tag("ocelot-unit")
class EventBufferTest {
  @Test
  @Timeout(30)
  def dropsAreCounted(): Unit = {
    val buffer = EventBuffer[Int]("drops", 3)
    try {
      (1 to 5).foreach(buffer.add)
      assertEquals(3, buffer.size)
      assertEquals(2L, buffer.dropped)
    } finally buffer.close()
  }

  @Test
  @Timeout(30)
  def theOldestIsDroppedFirst(): Unit = {
    val buffer = EventBuffer[Int]("order", 3)
    try {
      (1 to 5).foreach(buffer.add)
      assertEquals(Seq(3, 4, 5), buffer.snapshot)
    } finally buffer.close()
  }

  @Test
  @Timeout(30)
  def closeCancelsTheSubscription(): Unit = {
    val buffer = EventBuffer.subscribe[String]("crashes", 8) {
      case MachineCrashEvent(address, message) if address == "event-buffer-test" => message
    }
    EventBus.send(MachineCrashEvent("event-buffer-test", "before"))
    assertEquals(Seq("before"), buffer.snapshot)
    assertTrue(EventBuffer.open.contains("crashes"))
    buffer.close()
    EventBus.send(MachineCrashEvent("event-buffer-test", "after"))
    assertEquals(Seq("before"), buffer.snapshot)
    assertEquals(0L, buffer.offeredAfterClose, "events that reached the buffer after close")
    assertTrue(buffer.isClosed)
    assertFalse(EventBuffer.open.contains("crashes"))
  }
}
