package io.github.mindbound.opengpu.ocelot

import scala.collection.mutable

import totoro.ocelot.brain.event.{Event, EventBus}

/**
  * A bounded FIFO of records (05:115; S10 plan P10.3): past its capacity it drops the oldest record and counts the
  * drop, and [[close]] stops it recording and cancels its `EventBus` subscription, if it has one. What it is offered
  * after close is counted, not kept, so a subscription that close failed to cancel shows. RH's `SessionEventBuffer` is
  * the precedent (05:115). Thread-safe: OC's callbacks add from brain's executor threads, events arrive on any thread.
  */
final class EventBuffer[T] private (val name: String, val capacity: Int) extends AutoCloseable {
  require(capacity >= 1, s"$name: capacity $capacity")

  private val records = mutable.Queue.empty[T]
  private var drops = 0L
  private var late = 0L
  private var closed = false
  private var subscription: Option[EventBus.Subscription] = None

  def add(record: T): Unit = synchronized {
    if (closed) late += 1
    else {
      if (records.size >= capacity) {
        records.dequeue()
        drops += 1
      }
      records.enqueue(record)
    }
  }

  /** The records held now, oldest first. */
  def snapshot: Seq[T] = synchronized(records.toList)

  def size: Int = synchronized(records.size)

  /** Records dropped for capacity. */
  def dropped: Long = synchronized(drops)

  /** Records offered after close. */
  def offeredAfterClose: Long = synchronized(late)

  def isClosed: Boolean = synchronized(closed)

  override def close(): Unit = {
    val cancel = synchronized {
      if (closed) None
      else {
        closed = true
        val s = subscription
        subscription = None
        s
      }
    }
    cancel.foreach(s => EventBus.synchronized(s.cancel()))
    EventBuffer.opened.synchronized(EventBuffer.opened -= this)
  }

  override def toString: String = synchronized(s"EventBuffer[$name: ${records.size} of $capacity, $drops dropped]")
}

object EventBuffer {
  /** Buffers not yet closed, for ShutdownTest (S10 plan P14.9). */
  private val opened = mutable.LinkedHashSet.empty[EventBuffer[_]]

  /** A buffer with no subscription, such as the harness sink's records (S10 plan P8.1). */
  def apply[T](name: String, capacity: Int): EventBuffer[T] = {
    val buffer = new EventBuffer[T](name, capacity)
    opened.synchronized(opened += buffer)
    buffer
  }

  /** A buffer of the `EventBus` events `select` takes, subscribed until [[EventBuffer.close]]. */
  def subscribe[T](name: String, capacity: Int)(select: PartialFunction[Event, T]): EventBuffer[T] = {
    val buffer = apply[T](name, capacity)
    // EventBus keeps its listeners in a plain var (EventBus.scala:31-36, :76-78); subscribing and cancelling under one
    // lock keeps two threads from losing each other's change
    val subscription = EventBus.synchronized(EventBus.subscribe(select.andThen((record: T) => buffer.add(record))))
    buffer.synchronized(buffer.subscription = Some(subscription))
    buffer
  }

  /** The names of the buffers not yet closed. */
  def open: Seq[String] = opened.synchronized(opened.toList.map(_.name))
}
