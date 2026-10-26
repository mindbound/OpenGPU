package io.github.mindbound.opengpu.ocelot

import io.github.mindbound.opengpu.core.fixtures.device.ArgRows.NumberKind
import io.github.mindbound.opengpu.core.fixtures.device.{ArgRows, DeviceRig}
import io.github.mindbound.opengpu.ocelot.Expect.{ArgError, Message, Ok, Parity}
import org.junit.jupiter.api.Assertions.{assertEquals, fail}
import org.junit.jupiter.api.{Tag, Timeout}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/**
  * 28:920's argument rows through real OC (28:84, :1078; S10 plan P14.5; decisions D4), from `:core`'s ArgRows, which
  * the root's CallbackDispatchParityTest runs through OC's own dispatcher on the card (S11 plan P15.4; amendments F-2,
  * F-3): ±10^10 as floats, 10000000000 as written (an integer on 5.3 and 5.4, so a Long in Java), ±16.7, 0/0 and "x",
  * each in every integer argument of readPixels, createTexture, updateTexture and free, and a byte string with bytes of
  * 0x80 and above, a number, nil and a missing argument in submit's stream, updateTexture's data and createTexture's
  * format, the other arguments valid, on a T2 device under NoRenderHost. ArgRows' String row is the root's only, since
  * a Lua string reaches Java as `byte[]`. Each pcall result, values or error message, must equal what Java gets from
  * `:core`'s M0Replay, the shared model, on the arguments as OC converted them, which must be ArgRows' values for the
  * arch's number kind (28:1129), on a `DeviceRig` (P17.1: `DeviceCallbackTableTest` keeps the 133 rows of the model
  * itself). OC raises an argument error as `error(message, 0)` (NativeLuaArchitecture.scala:65-68;
  * machine.lua:1073-1074), so Lua sees the message with no decoration, and nothing is stripped. It also runs on LuaJ,
  * for correctness only (S10 decisions D5).
  */
@Tag("ocelot-pack")
@Tag("ocelot-instance")
@Tag("ocelot-luaj")
class ArgumentRowsTest {
  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#fullSet"))
  @Timeout(120)
  def rowsGiveWhatOcArgsAndTheDeviceGive(arch: Arch): Unit = {
    val kind = if (arch.integers) NumberKind.LONG else NumberKind.DOUBLE
    val script = new CallScript
    val calls = mutable.ArrayBuffer.empty[(Int, ArgRows.Call)]
    def call(c: ArgRows.Call, expect: Expect, keep: String = null): Unit =
      calls += script.call(c.callback, c.lua.asScala.toSeq: _*)(expect, keep) -> c
    call(ArgRows.SETUP, Ok, keep = ArgRows.SETUP.keep)
    for (site <- ArgRows.integerSites.asScala; row <- ArgRows.integerRows.asScala) {
      val expect =
        if (row.label == "0/0" || row.label == "\"x\"") ArgError(ArgumentRowsTest.argError(site.position, row.label))
        // 16.7 is 16 (28:84), so a 16 x 1 or 1 x 16 texture
        else if (site.callback == "createTexture" && row.label == "16.7") Ok
        else Parity
      call(site.`with`(row), expect)
    }
    val rows = ArgRows.byteRows.asScala.filterNot(_.isRootOnly)
    for (site <- ArgRows.byteSites.asScala; row <- rows) {
      call(site.`with`(row), ArgumentRowsTest.expect(site, row, kind))
    }
    val source = script.source
    val spec = RigSpec(s"ArgumentRowsTest-$arch", arch, "calls.lua", tier = 2,
      cases = i => if (i == 1) Some(source) else None)
    Rig.using(spec) { rig =>
      rig.awaitFinish()
      val outcomes = CallReplay.replay(DeviceRig.noRenderer(2), rig.sink, script.all)
      outcomes.find(!_.same).foreach(o => fail(s"$arch: $o"))
      outcomes.flatMap(_.missed).headOption.foreach(m => fail(s"$arch: $m"))
      // What OC delivered is ArgRows' values for the arch's kind: 10000000000 a Long where Lua has integers (28:1129),
      // a Double on 5.2 and LuaJ, and V.tex the handle the setup call returned
      val tex = outcomes.head.result.fold(e => throw new AssertionError(s"$arch: the setup call gave $e"), _.handle)
      val handles = Map(ArgRows.TEX -> Integer.valueOf(tex)).asJava
      for ((i, c) <- calls) {
        val expected = c.java(kind, handles)
        val delivered = outcomes(i - 1).luaArgs
        val same = expected.length == delivered.size &&
          expected.indices.forall(p => ArgRows.same(expected(p), delivered(p)))
        if (!same) {
          fail(s"$arch: ${outcomes(i - 1).call} delivered ${delivered.map(ArgumentRowsTest.show).mkString(", ")}, " +
            s"not ArgRows' ${expected.map(ArgumentRowsTest.show).mkString(", ")} for $kind")
        }
      }
      val big = outcomes.filter(_.call.args.startsWith("10000000000")).map(_.luaArgs.head.getClass.getSimpleName).toSet
      assertEquals(Set(if (arch.integers) "Long" else "Double"), big, "10000000000 as OC passed it")
      Report.line(s"ArgumentRowsTest $arch: ${outcomes.size} calls, ${ArgRows.integerRows.size} rows in every " +
        s"integer argument and ${rows.size} in every string argument, as M0Replay and the Java device give them, " +
        s"delivered as ArgRows' $kind values")
    }
  }
}

object ArgumentRowsTest {
  /** OC's message for NaN or a string in argument `position` (Arguments.scala:554-566; OcArgs.java:72-73). */
  def argError(position: Int, row: String): String =
    if (row == "0/0") s"bad argument #${position + 1} (number has no integer representation)"
    else s"bad argument #${position + 1} (integer expected, got string)"

  /**
    * What a string row must give at a site, beyond parity: submit takes the byte string's segment whole (CLEAR on T2)
    * and raises OC's argument errors for the others; createTexture's format is unsupported, or absent for nil and
    * missing, so a handle; updateTexture's data is checked by its length, which parity covers.
    */
  def expect(site: ArgRows.Site, row: ArgRows.Value, kind: NumberKind): Expect = {
    val n = site.position + 1
    val number = if (kind == NumberKind.LONG) "integer" else "number"
    (site.callback, row.label) match {
      case ("createTexture", "missing" | "nil") => Ok
      case (_, "missing") => ArgError(s"bad arguments #$n (string expected, got no value)")
      case (_, "nil") => ArgError(s"bad argument #$n (string expected, got nil)")
      case (_, "5") => ArgError(s"bad argument #$n (string expected, got $number)")
      case ("submit", _) => Ok
      case ("createTexture", _) => Message("unsupported format")
      case _ => Parity
    }
  }

  /** A delivered value for a message: its class and value, a byte string by its length. */
  def show(v: AnyRef): String = v match {
    case null => "nil"
    case b: Array[Byte] => s"byte[${b.length}]"
    case other => s"${other.getClass.getSimpleName} $other"
  }
}
