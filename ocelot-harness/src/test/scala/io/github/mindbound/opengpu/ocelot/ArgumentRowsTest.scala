package io.github.mindbound.opengpu.ocelot

import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig
import io.github.mindbound.opengpu.ocelot.Expect.{ArgError, Ok, Parity}
import org.junit.jupiter.api.Assertions.{assertEquals, fail}
import org.junit.jupiter.api.{Tag, Timeout}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
  * 28:920's argument rows through real OC (28:84, :1078; S10 plan P14.5; decisions D4): ±10^10 as floats,
  * 10000000000 as written (an integer on 5.3 and 5.4, so a Long in Java), ±16.7, 0/0 and "x", each in every integer
  * argument of readPixels, createTexture, updateTexture and free, the other arguments valid, on a T2 device under
  * NoRenderHost. Each pcall result, values or error message, must equal what Java gets from `:core`'s OcArgs model
  * of `checkInteger` on the arguments as OC converted them, then the same call on a `DeviceRig`
  * (P17.1: `DeviceCallbackTableTest` keeps the 133 rows of the model itself). OC raises an argument error as
  * `error(message, 0)` (NativeLuaArchitecture.scala:65-68; machine.lua:1073-1074), so Lua sees the message with no
  * decoration, and nothing is stripped. It also runs on LuaJ, for correctness only (S10 decisions D5).
  */
@Tag("ocelot-pack")
@Tag("ocelot-instance")
@Tag("ocelot-luaj")
class ArgumentRowsTest {
  @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#fullSet"))
  @Timeout(120)
  def rowsGiveWhatOcArgsAndTheDeviceGive(arch: Arch): Unit = {
    val script = new CallScript
    script.call("createTexture", "16", "16")(Ok, keep = "tex")
    val bases = Seq(
      "readPixels" -> Seq("0", "0", "1", "1"),
      "createTexture" -> Seq("1", "1"),
      "updateTexture" -> Seq("V.tex", "0", "0", "1", "1", "\"\\000\""),
      "free" -> Seq("0"))
    val integers = Map("readPixels" -> 4, "createTexture" -> 2, "updateTexture" -> 5, "free" -> 1)
    for ((name, base) <- bases; position <- 0 until integers(name); row <- ArgumentRowsTest.Rows) {
      val expect =
        if (row == "0/0" || row == "\"x\"") ArgError(ArgumentRowsTest.argError(position, row))
        // 16.7 is 16 (28:84), so a 16 x 1 or 1 x 16 texture
        else if (name == "createTexture" && row == "16.7") Ok
        else Parity
      script.call(name, base.updated(position, row): _*)(expect)
    }
    val source = script.source
    val spec = RigSpec(s"ArgumentRowsTest-$arch", arch, "calls.lua", tier = 2,
      cases = i => if (i == 1) Some(source) else None)
    Rig.using(spec) { rig =>
      rig.awaitFinish()
      val outcomes = CallReplay.replay(DeviceRig.noRenderer(2), rig.sink, script.all)
      outcomes.find(!_.same).foreach(o => fail(s"$arch: $o"))
      outcomes.flatMap(_.missed).headOption.foreach(m => fail(s"$arch: $m"))
      // 10000000000 reaches Java as a Long where Lua has integers (28:796), as a Double on 5.2
      val big = outcomes.filter(_.call.args.startsWith("10000000000")).map(_.luaArgs.head.getClass.getSimpleName).toSet
      assertEquals(Set(if (arch.integers) "Long" else "Double"), big, "10000000000 as OC passed it")
      Report.line(s"ArgumentRowsTest $arch: ${outcomes.size} calls, ${ArgumentRowsTest.Rows.size} rows in every " +
        "integer argument, as OcArgs and the Java device give them")
    }
  }
}

object ArgumentRowsTest {
  /** 28:920's rows as Lua source. */
  val Rows: Seq[String] = Seq("1e10", "-1e10", "10000000000", "16.7", "-16.7", "0/0", "\"x\"")

  /** OC's message for NaN or a string in argument `position` (Arguments.scala:554-566; OcArgs.java:72-73). */
  def argError(position: Int, row: String): String =
    if (row == "0/0") s"bad argument #${position + 1} (number has no integer representation)"
    else s"bad argument #${position + 1} (integer expected, got string)"
}
