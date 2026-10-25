package io.github.mindbound.opengpu.ocelot

import java.io.DataInputStream
import java.nio.file.Paths
import java.util.zip.ZipFile

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.{Tag, Test, Timeout}
import totoro.ocelot.brain.Settings

import scala.jdk.CollectionConverters._

/**
  * Every class in the brain jar the tests run on is major 52 or lower, so brain's Java half was compiled against Java
  * 8's class library and not, as by default, to major 61 (23 §4.3; 05:128): the check holds on either of the toolchain
  * routes S10's build documents (S10 plan P4.5, P14.1; N7).
  */
@Tag("ocelot-unit")
class ClassVersionTest {
  @Test
  @Timeout(30)
  def brainsClassesAreJava8s(): Unit = {
    val jar = Paths.get(classOf[Settings].getProtectionDomain.getCodeSource.getLocation.toURI)
    assertTrue(jar.getFileName.toString.endsWith(".jar"), s"brain comes from $jar, not the brain jar")
    val zip = new ZipFile(jar.toFile)
    val majors = try {
      zip.entries().asScala.filter(_.getName.endsWith(".class")).map { entry =>
        val in = new DataInputStream(zip.getInputStream(entry))
        try {
          assertEquals(0xCAFEBABE, in.readInt(), s"${entry.getName}'s magic")
          in.readUnsignedShort()
          entry.getName -> in.readUnsignedShort()
        } finally in.close()
      }.toList
    } finally zip.close()
    assertTrue(majors.nonEmpty, s"$jar holds no class")
    val above = majors.filter(_._2 > 52)
    assertEquals(Seq.empty, above.take(10), s"${above.size} of ${majors.size} classes above major 52")
    Report.line(s"ClassVersionTest: ${majors.size} classes in ${jar.getFileName}, majors " +
      s"${majors.map(_._2).distinct.sorted.mkString(", ")}")
  }
}
