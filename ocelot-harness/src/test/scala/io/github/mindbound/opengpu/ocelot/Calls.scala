package io.github.mindbound.opengpu.ocelot

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.collection.mutable

import io.github.mindbound.opengpu.core.cmd.ErrorCode
import io.github.mindbound.opengpu.core.device.CallResult
import io.github.mindbound.opengpu.core.fixtures.device.{DeviceRig, OcArgs}

/** What a call must give on the Java side, beyond being equal to what Lua got (S10 plan P14.4 (e)). */
sealed trait Expect

object Expect {
  /** Parity only. */
  case object Parity extends Expect

  /** Anything but `nil, msg`: true, a handle, pixels or a frame id. */
  case object Ok extends Expect

  /** A submit refused with this code at this offset (28 §3.6). */
  final case class Rejected(code: ErrorCode, offset: Int) extends Expect

  /** `nil, text`. */
  final case class Message(text: String) extends Expect

  /** OC's argument error, raised in Lua (28:84). */
  final case class ArgError(text: String) extends Expect
}

/** One call of a script: its index from 1, the callback, its Lua arguments for messages, and what it must give. */
final case class Call(index: Int, name: String, args: String, expect: Expect) {
  override def toString: String = s"call $index, $name(${if (args.length <= 120) args else args.take(117) + "..."})"
}

/**
  * A case batch for calls.lua (S10 plan P14.4 (d)-(e), P14.5, P14.6), written in Java so that the Lua side and the
  * Java replay take their inputs from one definition: each call is `call(i, "name", <Lua argument expressions>)`, which
  * can keep its first result in the batch's table V for a later call, and loops make runs of calls. On a host that
  * renders, [[await]] makes both sides wait for a presented frame, so the frames in flight are the same on both.
  */
final class CallScript {
  private val lines = mutable.ArrayBuffer.empty[String]
  private val calls = mutable.ArrayBuffer.empty[Call]
  private val waits = mutable.LinkedHashSet.empty[Int]

  /** One call; with `keep`, its first result goes to `V.<keep>`. Returns its index. */
  def call(name: String, args: String*)(expect: Expect, keep: String = null): Int = {
    val i = calls.size + 1
    calls += Call(i, name, args.mkString(", "), expect)
    val c = (Seq(i.toString, Lua.str(name)) ++ args).mkString("call(", ", ", ")")
    lines += (if (keep == null) c else s"V.$keep = select(2, $c)")
    i
  }

  /**
    * `n` calls in a Lua loop over k from 1 to n, whose arguments may use k; with `keep`, call k's first result goes to
    * `V[<keep> .. k]`. Returns their indices.
    */
  def loop(n: Int, name: String, args: String*)(expect: Expect, keep: String = null): Range = {
    val first = calls.size + 1
    (1 to n).foreach(k => calls += Call(first + k - 1, name, args.mkString(", ") + s" with k = $k", expect))
    val c = (Seq(s"${first - 1} + k", Lua.str(name)) ++ args).mkString("call(", ", ", ")")
    lines += s"for k = 1, $n do " + (if (keep == null) c else s"V[${Lua.str(keep)} .. k] = select(2, $c)") + " end"
    first until first + n
  }

  /**
    * Waits until the frame id in `V.<keep>` is confirmed, after the last call so far: Lua polls with harness.lua's
    * waitFrame, and the Java replay steps its host after that call, which folds the frame at once.
    */
  def await(keep: String): Unit = {
    require(calls.nonEmpty, "await before any call")
    lines += s"wait(V.$keep)"
    waits += calls.size
  }

  def all: Seq[Call] = calls.toList

  /** The indices of the calls after which the script waits for a frame. */
  def awaits: Set[Int] = waits.toSet

  /** The batch: a function of (E, call, wait), E being the char path's table. */
  def source: String = lines.mkString("return function(E, call, wait)\nlocal V = {}\n", "\n", "\nend\n")
}

/**
  * The Java side of calls.lua: each call replayed on a DeviceRig with the arguments as OC converted them, which Lua
  * put beside the call, through `:core`'s OcArgs, the model of OC's `checkInteger` (OcArgs.java:31, :83), and the
  * entity's other argument reads, in the entity's order (OpenGpuEntity); then both results described alike.
  */
object CallReplay {
  /** One call with what Lua got, what Java computed, and Java's result itself. */
  final case class Outcome(call: Call, luaArgs: Seq[AnyRef], lua: String, java: String,
      result: Either[String, CallResult]) {
    def same: Boolean = lua == java

    /** Why Java's own result misses the call's expectation, if it does. */
    def missed: Option[String] = (call.expect, result) match {
      case (Expect.Parity, _) => None
      case (Expect.Ok, Right(r)) if r.isOk => None
      case (Expect.Rejected(code, offset), Right(r)) if r.streamError() != null &&
          r.streamError().code == code && r.streamError().offset == offset => None
      case (Expect.Message(text), Right(r)) if !r.isOk && r.message() == text => None
      case (Expect.ArgError(text), Left(e)) if e == text => None
      case (expect, got) => Some(s"$call: expected $expect, Java gave ${got.fold(e => s"error $e", describe)}" +
          got.toOption.flatMap(r => Option(r.streamError())).map(e => s" (${e.code} at ${e.offset})").getOrElse(""))
    }

    override def toString: String = s"$call: Lua $lua, Java $java"
  }

  /** The calls in order, with `rig.step()` after each call in `stepAfter` ([[CallScript.awaits]]). */
  def replay(rig: DeviceRig, sink: HarnessSink, calls: Seq[Call], stepAfter: Set[Int] = Set.empty): Seq[Outcome] = {
    val args = sink.all("args").map(r => r.long(0) -> r).toMap
    val results = sink.all("call").map(r => r.long(0) -> r).toMap
    calls.map { c =>
      val a = args.getOrElse(c.index.toLong, throw new AssertionError(s"$c made no args record; ${sink.last(4)}"))
      val r = results.getOrElse(c.index.toLong, throw new AssertionError(s"$c made no call record; ${sink.last(4)}"))
      if (a.str(1) != c.name) throw new AssertionError(s"$c: Lua called ${a.str(1)}")
      val values = a.args.drop(2).toArray
      val java = run(rig, c.name, values)
      if (stepAfter.contains(c.index)) rig.step()
      Outcome(c, values.toSeq, lua(r.args.drop(1)), java.fold(e => s"error ${text(e)}", describe), java)
    }
  }

  /** What pcall returned: `error <message>`, or the values. */
  def lua(result: Seq[AnyRef]): String = result.headOption match {
    case Some(java.lang.Boolean.FALSE) => s"error ${describe(result.lift(1).orNull)}"
    case Some(java.lang.Boolean.TRUE) => result.drop(1).map(describe).mkString(", ")
    case other => s"<pcall gave $other>"
  }

  def describe(r: CallResult): String = r.toLua().toSeq.map(describe).mkString(", ")

  /** A value as Lua would print it: nil, a boolean, an integral number as an integer, or a string. */
  def describe(v: AnyRef): String = v match {
    case null => "nil"
    case b: java.lang.Boolean => b.toString
    case n: java.lang.Long => n.toString
    case n: java.lang.Integer => n.toString
    case n: java.lang.Number =>
      val d = n.doubleValue
      if (!d.isNaN && !d.isInfinite && d == math.rint(d) && math.abs(d) < 9.007199254740992e15) d.toLong.toString
      else d.toString
    case b: Array[Byte] => text(b)
    case s: String => text(s)
    case other => s"<${other.getClass.getName}>"
  }

  /** A short printable string as itself, quoted; anything else by its length and its sha256's first 16 digits. */
  def text(b: Array[Byte]): String =
    if (b.length <= 160 && b.forall(c => c >= 0x20 && c < 0x7f)) "\"" + new String(b, StandardCharsets.US_ASCII) + "\""
    else {
      val digest = MessageDigest.getInstance("SHA-256").digest(b)
      s"<${b.length} B ${digest.take(8).map(x => f"${x & 0xff}%02x").mkString}>"
    }

  def text(s: String): String = text(s.getBytes(StandardCharsets.UTF_8))

  private def run(rig: DeviceRig, name: String, args: Array[AnyRef]): Either[String, CallResult] = {
    val d = rig.device
    try Right(name match {
      case "submit" =>
        val s = byteArray(args, 0)
        rig.call(b => d.submit(b, s))
      case "present" => rig.call(b => d.present(b))
      case "readPixels" =>
        val x = OcArgs.checkInteger(args, 0)
        val y = OcArgs.checkInteger(args, 1)
        val w = OcArgs.checkInteger(args, 2)
        val h = OcArgs.checkInteger(args, 3)
        rig.call(b => d.readPixels(b, x, y, w, h))
      case "createTexture" =>
        val w = OcArgs.checkInteger(args, 0)
        val h = OcArgs.checkInteger(args, 1)
        val format = optString(args, 2)
        rig.call(b => d.createTexture(b, w, h, format))
      case "updateTexture" =>
        val tex = OcArgs.checkInteger(args, 0)
        val x = OcArgs.checkInteger(args, 1)
        val y = OcArgs.checkInteger(args, 2)
        val w = OcArgs.checkInteger(args, 3)
        val h = OcArgs.checkInteger(args, 4)
        val data = byteArray(args, 5)
        rig.call(b => d.updateTexture(b, tex, x, y, w, h, data))
      case "free" => d.free(OcArgs.checkInteger(args, 0))
      case other => throw new IllegalStateException(s"calls.lua has no Java replay of $other")
    })
    catch {
      case e: IllegalArgumentException => Left(e.getMessage)
    }
  }

  /** ocelot's `checkByteArray` (Arguments.scala:236-243): a string's bytes, else its argument errors. */
  private def byteArray(args: Array[AnyRef], i: Int): Array[Byte] = {
    if (i >= args.length) throw new IllegalArgumentException(s"bad arguments #${i + 1} (string expected, got no value)")
    args(i) match {
      case b: Array[Byte] => b
      case s: String => s.getBytes(StandardCharsets.UTF_8)
      case v => throw new IllegalArgumentException(s"bad argument #${i + 1} (string expected, got ${OcArgs.typeName(v)})")
    }
  }

  /** ocelot's `optString` (Arguments.scala:361-364): null when absent or nil, else `checkString`. */
  private def optString(args: Array[AnyRef], i: Int): String =
    if (i >= args.length || args(i) == null) null
    else args(i) match {
      case b: Array[Byte] => new String(b, StandardCharsets.UTF_8)
      case s: String => s
      case v => throw new IllegalArgumentException(s"bad argument #${i + 1} (string expected, got ${OcArgs.typeName(v)})")
    }
}
