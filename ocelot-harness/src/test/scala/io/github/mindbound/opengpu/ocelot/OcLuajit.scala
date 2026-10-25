package io.github.mindbound.opengpu.ocelot

import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue, fail}
import totoro.ocelot.brain.Settings

import scala.jdk.CollectionConverters._

/**
  * The local OC-LuaJIT leg's start (S10 plan P13.3; decisions D6), which Brain.init runs after Ocelot.initialize() and
  * before it reads the architectures:
  *  - ocluajitInputs' provenance record, the checkout's HEAD and every file's sha256, size and mtime, into ocelot.txt
  *    (P13.4);
  *  - OC-LuaJIT's patched kernel at its own path, from this build's ocluajitKernel: OcljArch only warns when it is
  *    missing, then boots OC's own kernel (OcljArch.scala:108-120);
  *  - OC's own kernel path served by the brain jar alone, so no kernel on the classpath shadows brain's;
  *  - exactly one native staged in `<runDir>/oclj-natives/`, as OC-LuaJIT's smoke-test.sh:521-528 stages one;
  *  - OC-LuaJIT's architecture registered as "LuaJIT", which setArchitecture needs (OcljArch.scala:837-849).
  * The adapter is compiled only for this leg, so it is reached by name.
  */
object OcLuajit {
  val Name: String = "LuaJIT"
  val ArchitectureClass: String = "ocljit.arch.OCLuaJITArchitecture"
  private val FactoryModule = "ocljit.arch.OCLuaJITStateFactory$"
  /** The run directory's folder that holds the staged native, the one forceNativeLibPathFirst names. */
  val NativeDir: String = "oclj-natives"
  val KernelResource: String = "/assets/ocluajit/lua/machine.lua"
  val OcKernelResource: String = "/assets/opencomputers/lua/machine.lua"

  def start(runDir: Path): Unit = {
    val provenance = Paths.get(Brain.property("opengpu.ocelot.ocluajitProvenance"))
    Files.readAllLines(provenance, StandardCharsets.UTF_8).asScala.filter(_.nonEmpty).foreach(Report.line)

    val loader = getClass.getClassLoader
    val kernel = Option(loader.getResource(KernelResource.drop(1))).getOrElse(fail[URL](
      s"$KernelResource is not on the classpath: ocluajitKernel puts OC-LuaJIT's patched kernel there (S10 decisions " +
        "D6), and without it OcljArch boots OC's own kernel with only a warning"))
    val resources = Paths.get(Brain.property("opengpu.ocelot.projectDir"), "build", "ocelot-luajit", "resources")
    assertEquals("file", kernel.getProtocol, s"$KernelResource comes from $kernel, not from ocluajitKernel's $resources")
    val file = Paths.get(kernel.toURI)
    assertTrue(file.startsWith(resources), s"$KernelResource comes from $file, not from ocluajitKernel's $resources")
    Report.line(s"OC-LuaJIT kernel $file: ${Files.size(file)} B, sha256 ${Report.sha256(file)}")

    val brainJar = classOf[Settings].getProtectionDomain.getCodeSource.getLocation
    val ocKernels = loader.getResources(OcKernelResource.drop(1)).asScala.map(_.toString).toList
    assertEquals(Seq(s"jar:$brainJar!$OcKernelResource"), ocKernels,
      s"$OcKernelResource on the leg's classpath: the brain jar's alone, or a kernel shadows brain's")

    val nativeDir = runDir.resolve(NativeDir)
    assertTrue(Files.isDirectory(nativeDir), s"no native is staged in $nativeDir: the leg sets opengpu.ocelot.stageNative")
    val staged = list(nativeDir)
    assertEquals(1, staged.size, s"$NativeDir holds ${staged.mkString(", ")}: forceNativeLibPathFirst must offer " +
      "exactly one native")

    val factory = Class.forName(FactoryModule).getField("MODULE$").get(null)
    factory.getClass.getMethod("register", classOf[String]).invoke(factory, Name)
    Report.line(s"OC-LuaJIT: ${staged.head.getFileName} staged alone in $NativeDir, OC's kernel only in the brain " +
      s"jar, architecture $Name registered")
  }

  private def list(dir: Path): Seq[Path] = {
    val entries = Files.list(dir)
    try entries.iterator().asScala.toList.sortBy(_.getFileName.toString) finally entries.close()
  }
}
