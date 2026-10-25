package io.github.mindbound.opengpu.ocelot

import java.nio.charset.StandardCharsets

import io.github.mindbound.opengpu.core.fixtures.TestEncoder

/** Lua source for the values the harness's Java side writes into case batches (S10 plan P14.3). */
object Lua {
  /**
    * A Lua string literal of the bytes: letters, digits and space as they are, every other byte as a three-digit
    * decimal escape, which every runtime reads alike (28:744's source-only load) and which 28 §9.1's scanner never
    * mistakes for code.
    */
  def str(bytes: Array[Byte]): String = {
    val out = new StringBuilder(bytes.length * 2 + 2).append('"')
    bytes.foreach { b =>
      val c = b & 0xff
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ') out.append(c.toChar)
      else out.append('\\').append(f"$c%03d")
    }
    out.append('"').toString
  }

  def str(s: String): String = str(s.getBytes(StandardCharsets.UTF_8))

  /** A u32 operand, such as a colour or a handle, as Lua's non-negative integer literal. */
  def u32(v: Int): String = Integer.toUnsignedString(v)
}

/**
  * One 2D record of 28 §5.2, as the Lua call of 28 §9.2's builder that encodes it and as `:core`'s TestEncoder bytes,
  * which mirror those builders (TestEncoder.java:8-22), from one definition. A BLIT names its texture by a handle or
  * by the scene's name for it, which the Lua side looks up with `S.tex(name)` and the Java side in its replay. In-range
  * integers only, the encoder's contract (28:755): i16 fields in -32 768 to 32 767, u32 ones as the int's 32 bits.
  */
sealed trait Rec {
  /** The builder call on encoder table `e`, such as `E.fill(10, 20, 100, 50, 196)`. */
  def lua(e: String): String

  /** The record's bytes, with `handle` giving a named texture's handle. */
  def bytes(handle: String => Int): Array[Byte]

  def bytes: Array[Byte] = bytes(name => throw new IllegalStateException(s"$this names texture $name"))
}

object Rec {
  /** A texture by handle or by a scene's name. */
  sealed trait Tex {
    def lua: String
    def handle(named: String => Int): Int
  }

  final case class Handle(value: Int) extends Tex {
    override def lua: String = Lua.u32(value)
    override def handle(named: String => Int): Int = value
    override def toString: String = f"0x$value%08X"
  }

  final case class Named(name: String) extends Tex {
    override def lua: String = s"S.tex(${Lua.str(name)})"
    override def handle(named: String => Int): Int = named(name)
    override def toString: String = name
  }

  final case class Clear(c: Int) extends Rec {
    override def lua(e: String): String = s"$e.clear(${Lua.u32(c)})"
    override def bytes(handle: String => Int): Array[Byte] = TestEncoder.clear(c)
    override def toString: String = f"CLEAR 0x$c%08X"
  }

  final case class Fill(x: Int, y: Int, w: Int, h: Int, c: Int) extends Rec {
    override def lua(e: String): String = s"$e.fill($x, $y, $w, $h, ${Lua.u32(c)})"
    override def bytes(handle: String => Int): Array[Byte] = TestEncoder.fill(x, y, w, h, c)
    override def toString: String = f"FILL_RECT $x, $y, $w x $h, 0x$c%08X"
  }

  final case class Blit(tex: Tex, sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int, flags: Int) extends Rec {
    override def lua(e: String): String = s"$e.blit(${tex.lua}, $sx, $sy, $w, $h, $dx, $dy, $flags)"
    override def bytes(handle: String => Int): Array[Byte] =
      TestEncoder.blit(tex.handle(handle), sx, sy, w, h, dx, dy, flags)
    override def toString: String = s"BLIT $tex, $sx, $sy, $w x $h to $dx, $dy, flags $flags"
  }

  final case class Text(x: Int, y: Int, fg: Int, bg: Int, flags: Int, font: Int, s: Array[Byte]) extends Rec {
    override def lua(e: String): String =
      s"$e.text($x, $y, ${Lua.u32(fg)}, ${Lua.u32(bg)}, $flags, $font, ${Lua.str(s)})"
    override def bytes(handle: String => Int): Array[Byte] = TestEncoder.text(x, y, fg, bg, flags, font, s)
    override def toString: String = f"TEXT $x, $y, fg 0x$fg%08X, bg 0x$bg%08X, flags $flags, font $font, ${s.length} B"
  }

  def text(x: Int, y: Int, fg: Int, bg: Int, flags: Int, font: Int, s: String): Text =
    Text(x, y, fg, bg, flags, font, s.getBytes(StandardCharsets.UTF_8))
}

/** Failure messages for byte comparisons (S10 plan P14: the first differing offset and bounded hex on both sides). */
object Bytes {
  /** None when equal; else the first differing offset, the lengths and up to 16 bytes from there on each side. */
  def difference(expected: Array[Byte], actual: Array[Byte]): Option[String] = {
    val n = math.min(expected.length, actual.length)
    var i = 0
    while (i < n && expected(i) == actual(i)) i += 1
    if (i == n && expected.length == actual.length) None
    else {
      Some(s"first difference at offset $i (expected ${expected.length} B, got ${actual.length} B): expected " +
        s"${window(expected, i)}, got ${window(actual, i)}")
    }
  }

  /** Up to 16 bytes from `at` as hex, or "end". */
  def window(bytes: Array[Byte], at: Int): String =
    if (at >= bytes.length) "end" else TestEncoder.toHex(bytes.slice(at, math.min(bytes.length, at + 16)))

  def concat(parts: Seq[Array[Byte]]): Array[Byte] = TestEncoder.concat(parts: _*)
}
