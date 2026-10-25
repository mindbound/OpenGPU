package io.github.mindbound.opengpu.ocelot

import java.io.DataInputStream
import java.nio.file.{Files, Path, Paths}

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.{Tag, Test, Timeout}

import scala.jdk.CollectionConverters._

/**
  * The adapter the OC-LuaJIT leg compiled from the owner's checkout (S10 plan P13.3, P14.1): LuaStateLuaJIT with its
  * nested LuaDebug, which OC-LuaJIT's smoke-test.sh:480 asserts, and OCLuaJITArchitecture each come from this build's
  * own output, not from a jar on the classpath, and every class there is major 52 or lower, as ClassVersionTest checks
  * brain's (N7): javac on JDK 17 with release 8 and Zinc on Java 8 (S10 amendments I14).
  */
@Tag("ocelot-luajit")
class OcLuajitTest {
  @Test
  @Timeout(30)
  def theAdapterIsThisBuildsAndJava8s(): Unit = {
    val build = Paths.get(Brain.property("opengpu.ocelot.projectDir"), "build", "classes")
    val state = output("li.cil.repack.com.naef.jnlua.LuaStateLuaJIT", build.resolve("java").resolve("luajit"))
    val arch = output(OcLuajit.ArchitectureClass, build.resolve("scala").resolve("luajit"))
    val debug = state.resolve("li/cil/repack/com/naef/jnlua/LuaStateLuaJIT$LuaDebug.class")
    assertTrue(Files.isRegularFile(debug), s"LuaStateLuaJIT compiled without its nested LuaDebug: no $debug")
    val majors = Seq(state, arch).flatMap(classes).map(f => f -> major(f))
    val above = majors.filter(_._2 > 52)
    assertEquals(Seq.empty, above.take(10), s"${above.size} of ${majors.size} classes above major 52")
    Report.line(s"OcLuajitTest: ${majors.size} classes in $state and $arch, majors " +
      s"${majors.map(_._2).distinct.sorted.mkString(", ")}")
  }

  /** The classes directory `name` was loaded from, which must be `expected`. */
  private def output(name: String, expected: Path): Path = {
    val cls = Class.forName(name, false, getClass.getClassLoader)
    val from = Paths.get(cls.getProtectionDomain.getCodeSource.getLocation.toURI)
    assertEquals(expected, from, s"$name comes from $from")
    from
  }

  private def classes(dir: Path): Seq[Path] = {
    val paths = Files.walk(dir)
    try paths.iterator().asScala.filter(_.getFileName.toString.endsWith(".class")).toList finally paths.close()
  }

  private def major(file: Path): Int = {
    val in = new DataInputStream(Files.newInputStream(file))
    try {
      assertEquals(0xCAFEBABE, in.readInt(), s"$file's magic")
      in.readUnsignedShort()
      in.readUnsignedShort()
    } finally in.close()
  }
}
