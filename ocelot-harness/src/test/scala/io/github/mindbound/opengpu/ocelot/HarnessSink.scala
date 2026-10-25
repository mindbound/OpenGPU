package io.github.mindbound.opengpu.ocelot

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import totoro.ocelot.brain.entity.machine.{Arguments, Callback, Context}
import totoro.ocelot.brain.entity.traits.{Entity, Environment}
import totoro.ocelot.brain.nbt.NBTTagCompound
import totoro.ocelot.brain.network.{Component, Network, Visibility}

/**
  * What one `put` handed over (S10 plan P8.1): its name and the remaining arguments as OC converted them, Lua strings
  * as `byte[]`, numbers as Long (Lua integers) or Double, nil as null, with the harness tick it arrived in.
  */
final case class SinkRecord(name: String, args: IndexedSeq[AnyRef], tick: Long, thread: String) {
  def isNil(i: Int): Boolean = i >= args.size || args(i) == null

  /** A string argument, decoded as UTF-8. */
  def str(i: Int): String = arg(i) match {
    case b: Array[Byte] => new String(b, StandardCharsets.UTF_8)
    case other => throw new IllegalArgumentException(s"$this: argument $i is $other, not a string")
  }

  def bytes(i: Int): Array[Byte] = arg(i) match {
    case b: Array[Byte] => b
    case other => throw new IllegalArgumentException(s"$this: argument $i is $other, not a string")
  }

  def number(i: Int): Double = arg(i) match {
    case n: java.lang.Number => n.doubleValue
    case other => throw new IllegalArgumentException(s"$this: argument $i is $other, not a number")
  }

  /** A number argument that must be integral. */
  def long(i: Int): Long = {
    val d = number(i)
    require(d == math.rint(d) && !d.isInfinite, s"$this: argument $i is $d, not an integer")
    d.toLong
  }

  def bool(i: Int): Boolean = arg(i) match {
    case b: java.lang.Boolean => b.booleanValue
    case other => throw new IllegalArgumentException(s"$this: argument $i is $other, not a boolean")
  }

  private def arg(i: Int): AnyRef = if (i < args.size) args(i) else null

  /** Strings shown up to 32 bytes and their length beyond, so a failure message stays bounded. */
  override def toString: String = args.map {
    case null => "nil"
    case b: Array[Byte] if b.length <= 32 => "\"" + new String(b, StandardCharsets.UTF_8) + "\""
    case b: Array[Byte] => s"<${b.length} B>"
    case other => String.valueOf(other)
  }.mkString(s"$name(", ", ", s") at tick $tick")
}

/** The script's `finish(ok, msg)`: the BIOS's, or a helper's that gave up first. */
final case class Finish(ok: Boolean, message: String)

/**
  * The `ogpu_harness` component (S10 plan P8.1, after the spike's sink), through which a test's Lua takes its script,
  * parameters, case batches and blobs and hands back what it measured. Every callback but [[sync]] is direct, with
  * the default limit, so each costs 1/2^31 of the call budget (Machine.scala:347-349). Records go into a bounded
  * EventBuffer of 8 192 slots, and only the first `finish` counts.
  *
  * @param source the whole chunk the BIOS loads: ogpu_builders.lua, harness.lua, the rig's libraries and the test's
  *               own file (P8.2)
  * @param caseBatches the Lua source of case batch i, from 1, or None past the last (P14.3)
  * @param onHold what [[hold]] does: the rig's gate on the host's pumps
  */
final class HarnessSink(
    source: Array[Byte],
    params: Map[String, Any],
    blobs: Map[String, Array[Byte]],
    caseBatches: Int => Option[String],
    ticks: () => Long,
    onHold: Boolean => Unit = _ => ())
  extends Entity with Environment {

  override val node: Component = Network.newNode(this, Visibility.Network)
    .withComponent("ogpu_harness", Visibility.Network)
    .create()

  val records: EventBuffer[SinkRecord] = EventBuffer("ogpu_harness records", HarnessSink.Slots)

  private val finished = new AtomicReference[Finish]()
  private val boots = new AtomicInteger()
  private val syncs = new AtomicInteger()
  private val gos = new AtomicInteger()
  private val releases = new AtomicInteger()
  private val parts: IndexedSeq[Array[Byte]] = source.grouped(HarnessSink.PartBytes).toIndexedSeq

  @Callback(direct = true, doc = "function(i:number):string or nil -- part i of the test's Lua source")
  def script(context: Context, args: Arguments): Array[AnyRef] = {
    val i = args.checkInteger(0)
    if (i == 1) boots.incrementAndGet()
    Array(if (i >= 1 && i <= parts.size) parts(i - 1) else null)
  }

  /** The BIOS's run of the script: 1, then 2 after a reboot, as each run asks for part 1 first (S10 plan P14.8). */
  @Callback(direct = true, doc = "function():number -- which boot of the machine this run of the script is")
  def boot(context: Context, args: Arguments): Array[AnyRef] = Array(Integer.valueOf(boots.get))

  /**
    * Holds the host's pumps, or releases them. A hold returns once any pump that was running has ended, so the records
    * the script offers next stay queued until the release (S10 plan P14.8's two frames in flight; 28:144).
    */
  @Callback(direct = true, doc = "function(on:boolean):boolean -- holds or releases the host's pumps")
  def hold(context: Context, args: Arguments): Array[AnyRef] = {
    onHold(args.checkBoolean(0))
    Array(java.lang.Boolean.TRUE)
  }

  @Callback(direct = true, doc = "function(name:string):number or string or nil -- a test parameter")
  def param(context: Context, args: Arguments): Array[AnyRef] =
    Array(params.get(args.checkString(0)).map(_.asInstanceOf[AnyRef]).orNull)

  @Callback(direct = true, doc = "function(i:number):string or nil -- the Lua source of case batch i")
  def cases(context: Context, args: Arguments): Array[AnyRef] =
    Array(caseBatches(args.checkInteger(0)).map(_.getBytes(StandardCharsets.UTF_8)).orNull)

  @Callback(direct = true, doc = "function(name:string):string or nil -- a byte string, such as a sprite")
  def blob(context: Context, args: Arguments): Array[AnyRef] = Array(blobs.get(args.checkString(0)).orNull)

  @Callback(direct = true, doc = "function(name:string, ...):boolean -- records the arguments as OC converted them")
  def put(context: Context, args: Arguments): Array[AnyRef] = {
    records.add(SinkRecord(args.checkString(0), args.args.drop(1).toIndexedSeq, ticks(),
      Thread.currentThread().getName))
    Array(java.lang.Boolean.TRUE)
  }

  @Callback(direct = true, doc = "function(ok:boolean, msg:string):boolean -- the script's result; the first counts")
  def finish(context: Context, args: Arguments): Array[AnyRef] = {
    finished.compareAndSet(null, Finish(args.optBoolean(0, false), args.optString(1, "")))
    Array(java.lang.Boolean.TRUE)
  }

  /**
    * Not direct, so OC runs it as a synchronized call during the next `update()`: a script that must not take a signal
    * from the queue yields one tick this way. A harness [proposal] that F11's queue test (SignalQueueTest) and
    * harness.lua's `park(n)` (S10 amendments F11, I2) use.
    */
  @Callback(doc = "function():boolean -- returns during the next tick, as a synchronized call")
  def sync(context: Context, args: Arguments): Array[AnyRef] = {
    syncs.incrementAndGet()
    Array(java.lang.Boolean.TRUE)
  }

  /**
    * harness.lua's `park(n)` asks this after each `sync()`: true once per [[release]], else false (S10 amendments I2).
    */
  @Callback(direct = true, doc = "function():boolean -- whether Java let the parked script go")
  def go(context: Context, args: Arguments): Array[AnyRef] = {
    val going = releases.getAndUpdate(n => math.max(0, n - 1)) > 0
    gos.incrementAndGet()
    Array(java.lang.Boolean.valueOf(going))
  }

  override def save(nbt: NBTTagCompound): Unit = throw new UnsupportedOperationException("S10: no persistence; M1")

  override def dispose(): Unit = {
    records.close()
    super.dispose()
  }

  // ---- What the tests read

  def result: Option[Finish] = Option(finished.get)

  /**
    * Every `sync()` that ran has had its `go()`: a script in `park(n)` that has also left the executor waits in its
    * next `sync()` for a tick (a sync that ran but whose go has not come yet means the executor is due).
    */
  def syncsAnswered: Boolean = gos.get == syncs.get

  /** Lets a parked script go at its next `go()`. */
  def release(): Unit = releases.incrementAndGet()

  def all(name: String): Seq[SinkRecord] = records.snapshot.filter(_.name == name)

  def count(name: String): Int = all(name).size

  def first(name: String): SinkRecord =
    all(name).headOption.getOrElse(throw new AssertionError(s"no $name record; the last ones: ${last(8)}"))

  /**
    * harness.lua's `putJoined(name, key, pieces)`: the runs put under `key`, joined in run order.
    *
    * @throws AssertionError when its end record is missing or the runs are not numbered 1 to n in order
    */
  def joined(name: String, key: Long): Array[Byte] = {
    val end = all(name + "End").find(_.long(0) == key)
      .getOrElse(throw new AssertionError(s"putJoined($name, $key) did not end; the last records: ${last(6)}"))
    val runs = all(name).filter(_.long(0) == key)
    if (runs.map(_.long(1)) != (1L to end.long(1)).toList) {
      throw new AssertionError(s"putJoined($name, $key): runs ${runs.map(_.long(1)).mkString(", ")}, ended at $end")
    }
    val out = new ByteArrayOutputStream()
    runs.foreach(r => out.write(r.bytes(2)))
    out.toByteArray
  }

  /** The last `n` records, for a failure message. */
  def last(n: Int): String = records.snapshot.takeRight(n).mkString("; ")

  /**
    * harness.lua's `readAll(tag)`: the surface's bytes, joined in row order, and the frame id of each chunk.
    *
    * @throws AssertionError when its rowsEnd is missing or the chunks do not cover the rows in order
    */
  def rows(tag: String): (Array[Byte], Seq[Long]) = {
    val end = all("rowsEnd").find(_.str(0) == tag).getOrElse(throw new AssertionError(s"readAll($tag) did not end"))
    val chunks = all("rows").filter(_.str(0) == tag)
    if (chunks.size != end.long(1)) throw new AssertionError(s"readAll($tag): ${chunks.size} chunks, ended at $end")
    val out = new ByteArrayOutputStream()
    var y = 0L
    chunks.foreach { c =>
      if (c.long(1) != y) throw new AssertionError(s"readAll($tag): chunk $c starts at row ${c.long(1)}, not $y")
      out.write(c.bytes(4))
      y += c.long(2)
    }
    (out.toByteArray, chunks.map(_.long(3)))
  }

  /** harness.lua's `putChunked(name, s)`: the string, joined in part order. */
  def chunked(name: String): Array[Byte] = {
    val end = first(name + "End")
    val parts = all(name)
    if (parts.size != end.long(0)) throw new AssertionError(s"putChunked($name): ${parts.size} parts, ended at $end")
    val out = new ByteArrayOutputStream()
    parts.zipWithIndex.foreach { case (p, i) =>
      if (p.long(0) != i + 1) throw new AssertionError(s"putChunked($name): part $p, not ${i + 1}")
      out.write(p.bytes(1))
    }
    val bytes = out.toByteArray
    if (bytes.length != end.long(1)) throw new AssertionError(s"putChunked($name): ${bytes.length} B, ended at $end")
    bytes
  }
}

object HarnessSink {
  val Slots: Int = 8192

  /** The size of the parts `script(i)` returns (S10 plan P8.1). */
  val PartBytes: Int = 16384
}
