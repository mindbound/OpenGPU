package io.github.mindbound.opengpu.ocelot

import java.nio.charset.StandardCharsets
import java.util.SplittableRandom

import scala.collection.mutable

import io.github.mindbound.opengpu.ocelot.Rec.{Blit, Clear, Fill, Handle, Text}

/**
  * EncoderIdentityTest's corpus (S10 plan P14.3; 28:904, :907), built in Java from a seed: the edges first, then the
  * random records, all in range (28:755).
  *  - every i16 field at 28:904's edges, -32 768, -1, 0, 1, W - 1, W and 32 767, with T2's and T3's W (or H) for its
  *    axis, the other fields at a base value;
  *  - colours at the edges of index8 and of rgb565's quantization (28:495's q steps at 4/5 and 2/3 of 255, and their
  *    mirrors), the channels alone, and the u32 edges, in every colour field;
  *  - the handles 0x08000000 and 0x7FFFFFFF, with 0, 1, 0x80000000 and 0xFFFFFFFF;
  *  - flags and font at 0, 1, 2, 127, 128 and 255;
  *  - TEXT with n = 0, 1, 255, 256, 257 and 1 024, with 1- to 4-byte UTF-8 sequences, and with every byte value once;
  *  - `cases` random records (`opengpu.ocelot.cases`, 2 000 by default) from `seed` (`opengpu.ocelot.seed`, 28).
  */
object EncoderCorpus {
  /** Records per case batch (S10 plan P14.3). */
  val BatchSize: Int = 100

  private val XEdges = Seq(-32768, -1, 0, 1, 319, 320, 639, 640, 32767)
  private val YEdges = Seq(-32768, -1, 0, 1, 199, 200, 399, 400, 32767)
  private val Colours = Seq(0, 1, 127, 128, 254, 255, 0xFF000000, 0xFFFFFFFF, 0xFF040404, 0xFF050505, 0xFF020202,
    0xFF030303, 0xFFFBFBFB, 0xFFFAFAFA, 0xFFFDFDFD, 0xFFFC0000, 0xFF00FC00, 0xFF0000FC, 0x7FFFFFFF, 0x80000000,
    0xFFFFFFFE)
  private val Handles = Seq(0x08000000, 0x7FFFFFFF, 0, 1, 0x80000000, 0xFFFFFFFF)
  private val Bytes8 = Seq(0, 1, 2, 127, 128, 255)

  def seed: Long = java.lang.Long.parseLong(System.getProperty("opengpu.ocelot.seed", "28"))

  def cases: Int = Integer.parseInt(System.getProperty("opengpu.ocelot.cases", "2000"))

  /** The corpus: the edges, then `cases` random records from `seed`. */
  def records(seed: Long = seed, cases: Int = cases): IndexedSeq[Rec] = edges ++ random(seed, cases)

  /** The records in batches of [[BatchSize]], as case batch sources for encode.lua. */
  def batches(records: IndexedSeq[Rec]): IndexedSeq[IndexedSeq[Rec]] = records.grouped(BatchSize).toIndexedSeq

  /** A batch's Lua source: a function of the encoder table, returning the batch's records in order. */
  def source(batch: Seq[Rec]): String =
    batch.map(_.lua("E")).mkString("return function(E) return {\n", ",\n", "\n} end\n")

  def edges: IndexedSeq[Rec] = {
    val out = mutable.ArrayBuffer.empty[Rec]
    // i16 fields, one at a time
    XEdges.foreach(v => out += Fill(v, 5, 7, 9, 196))
    YEdges.foreach(v => out += Fill(5, v, 7, 9, 196))
    XEdges.foreach(v => out += Fill(5, 6, v, 9, 196))
    YEdges.foreach(v => out += Fill(5, 6, 7, v, 196))
    XEdges.foreach(v => out += Blit(Handle(0x08000000), v, 2, 3, 4, 5, 6, 1))
    YEdges.foreach(v => out += Blit(Handle(0x08000000), 1, v, 3, 4, 5, 6, 1))
    XEdges.foreach(v => out += Blit(Handle(0x08000000), 1, 2, v, 4, 5, 6, 1))
    YEdges.foreach(v => out += Blit(Handle(0x08000000), 1, 2, 3, v, 5, 6, 1))
    XEdges.foreach(v => out += Blit(Handle(0x08000000), 1, 2, 3, 4, v, 6, 1))
    YEdges.foreach(v => out += Blit(Handle(0x08000000), 1, 2, 3, 4, 5, v, 1))
    XEdges.foreach(v => out += Rec.text(v, 7, 255, 0, 1, 0, "Hi"))
    YEdges.foreach(v => out += Rec.text(7, v, 255, 0, 1, 0, "Hi"))
    // colours, in each colour field
    Colours.foreach { c =>
      out += Clear(c)
      out += Fill(10, 20, 100, 50, c)
      out += Rec.text(8, 180, c, 0, 0, 0, "fg")
      out += Rec.text(8, 180, 255, c, 0, 0, "bg")
    }
    Handles.foreach(t => out += Blit(Handle(t), 0, 0, 16, 16, 150, 40, 0))
    Bytes8.foreach { v =>
      out += Blit(Handle(0x08000000), 0, 0, 16, 16, 150, 40, v)
      out += Rec.text(8, 180, 255, 0, v, 0, "flags")
      out += Rec.text(8, 180, 255, 0, 0, v, "font")
    }
    // TEXT's count and strings
    Seq(0, 1, 255, 256, 257, 1024).foreach(n => out += Text(1, 2, 255, 0, 0, 0, Array.fill[Byte](n)('x'.toByte)))
    Seq("A", "é", "€", "😀", "Aé€😀", "é" * 512, "😀" * 256)
      .foreach(s => out += Rec.text(1, 2, 255, 0, 0, 0, s))
    out += Text(1, 2, 255, 0, 0, 0, Array.tabulate[Byte](256)(_.toByte))
    out.toIndexedSeq
  }

  /** `n` in-range records drawn from `new SplittableRandom(seed)`, kinds weighted CLEAR 1, the others 3 each. */
  def random(seed: Long, n: Int): IndexedSeq[Rec] = {
    val r = new SplittableRandom(seed)
    def i16(): Int = r.nextInt(65536) - 32768
    def u32(): Int = r.nextInt()
    def u8(): Int = r.nextInt(256)
    IndexedSeq.fill(n) {
      r.nextInt(10) match {
        case 0 => Clear(u32())
        case 1 | 2 | 3 => Fill(i16(), i16(), i16(), i16(), u32())
        case 4 | 5 | 6 => Blit(Handle(u32()), i16(), i16(), i16(), i16(), i16(), i16(), u8())
        case _ => Text(i16(), i16(), u32(), u32(), u8(), u8(), string(r))
      }
    }
  }

  /** 0 to 64 codepoints of 1 to 4 UTF-8 bytes, or now and then 0 to 64 bytes of any value. */
  private def string(r: SplittableRandom): Array[Byte] =
    if (r.nextInt(8) == 0) Array.fill[Byte](r.nextInt(65))(r.nextInt(256).toByte)
    else {
      val b = new java.lang.StringBuilder
      (0 until r.nextInt(65)).foreach { _ =>
        val cp = r.nextInt(4) match {
          case 0 => 0x20 + r.nextInt(0x5F)
          case 1 => 0x80 + r.nextInt(0x780)
          case 2 => 0x800 + r.nextInt(0xD000)
          case _ => 0x10000 + r.nextInt(0x100000)
        }
        b.appendCodePoint(cp)
      }
      b.toString.getBytes(StandardCharsets.UTF_8)
    }
}
