package io.github.mindbound.opengpu.ocelot

import scala.collection.mutable

/**
  * Flags the constructs 28 §9.1 rules out of the chunk that runs on 5.2, 5.3, 5.4, LuaJ and OC-LuaJIT alike
  * (28:739-747; S10 plan P8.4), outside comments and strings: `//`, the bitwise operators and `bit32`, which 5.2 cannot
  * parse or the tracer cannot inline; `string.format` with `%d` or `%i`, which 5.2 raises at 2^31 and above; every
  * pattern function [proposal, stricter than "on buffers"]; `computer.realTime`; a variadic `string.char` (of `unpack`
  * or `...`); and `1LL` literals. It reads tokens, not meaning, so it cannot check that every operand is floored or
  * that a `table.concat` stays within 4 KB, and claims neither (S10 amendments F17).
  */
object LuaScanner {
  final case class Finding(rule: String, line: Int, near: String) {
    override def toString: String = s"line $line: $rule ($near)"
  }

  private final case class Token(kind: Char, text: String, line: Int) // kind: n(ame) s(tring) d(igits) o(perator)

  private val PatternFunctions = Set("find", "match", "gmatch", "gsub")
  private val Bitwise = Set("&", "|", "<<", ">>", "~")

  def scan(source: String): Seq[Finding] = {
    val tokens = lex(source)
    val found = mutable.ArrayBuffer.empty[Finding]
    def at(i: Int): Option[Token] = if (i >= 0 && i < tokens.size) Some(tokens(i)) else None
    def isOp(i: Int, text: String): Boolean = at(i).exists(t => t.kind == 'o' && t.text == text)
    def isName(i: Int, text: String): Boolean = at(i).exists(t => t.kind == 'n' && t.text == text)
    def afterDot(i: Int): Boolean = isOp(i - 1, ".") || isOp(i - 1, ":")
    def formatsInteger(i: Int): Boolean = at(i).exists(t => t.kind == 's' && (t.text.contains("%d") ||
      t.text.contains("%i") || t.text.matches("(?s).*%[-+ #0-9.]+[di].*")))
    tokens.indices.foreach { i =>
      val t = tokens(i)
      def flag(rule: String): Unit = found += Finding(rule, t.line, t.text)
      t.kind match {
        case 'o' if t.text == "//" => flag("floor division")
        case 'o' if Bitwise(t.text) => flag("bitwise operator")
        case 'n' if t.text == "bit32" => flag("bit32")
        case 'n' if t.text == "realTime" && afterDot(i) => flag("computer.realTime")
        case 'n' if PatternFunctions(t.text) && afterDot(i) => flag("pattern function")
        // string.format("%d", ...) and ("%d"):format(...)
        case 'n' if t.text == "format" && afterDot(i) && ((isOp(i + 1, "(") && formatsInteger(i + 2)) ||
            (isOp(i - 1, ":") && isOp(i - 2, ")") && formatsInteger(i - 3))) =>
          flag("string.format with %d")
        case 'n' if t.text == "char" && isOp(i + 1, "(") &&
          (isName(i + 2, "unpack") || isOp(i + 2, "...") || (isName(i + 2, "table") && isName(i + 4, "unpack"))) =>
          flag("variadic string.char")
        case 'd' if t.text.toUpperCase.endsWith("LL") => flag("LL literal")
        case _ =>
      }
    }
    found.toList
  }

  /** Names, strings (their contents), numbers and operators, with comments and long brackets dropped. */
  private def lex(s: String): IndexedSeq[Token] = {
    val out = mutable.ArrayBuffer.empty[Token]
    var i = 0
    var line = 1
    def peek(k: Int): Char = if (i + k < s.length) s.charAt(i + k) else '\u0000'
    def advance(n: Int): Unit = {
      var k = 0
      while (k < n && i < s.length) {
        if (s.charAt(i) == '\n') line += 1
        i += 1
        k += 1
      }
    }
    /** At "[", "[=", "[==", ...: the level of a long bracket that opens here, or -1. */
    def longOpen(): Int = {
      if (peek(0) != '[') -1
      else {
        var k = 1
        while (peek(k) == '=') k += 1
        if (peek(k) == '[') k - 1 else -1
      }
    }
    def skipLong(level: Int): String = {
      advance(level + 2)
      val close = "]" + "=" * level + "]"
      val end = s.indexOf(close, i)
      val stop = if (end < 0) s.length else end
      val text = s.substring(i, stop)
      advance(stop - i + (if (end < 0) 0 else close.length))
      text
    }
    while (i < s.length) {
      val c = peek(0)
      val start = line
      if (c == '-' && peek(1) == '-') {
        advance(2)
        val level = longOpen()
        if (level >= 0) skipLong(level)
        else while (i < s.length && peek(0) != '\n') advance(1)
      } else if (c == '"' || c == '\'') {
        val text = new StringBuilder
        advance(1)
        while (i < s.length && peek(0) != c && peek(0) != '\n') {
          if (peek(0) == '\\') {
            text.append(peek(0)).append(peek(1))
            advance(2)
          } else {
            text.append(peek(0))
            advance(1)
          }
        }
        advance(1)
        out += Token('s', text.toString, start)
      } else if (c == '[' && longOpen() >= 0) {
        out += Token('s', skipLong(longOpen()), start)
      } else if (c.isLetter || c == '_') {
        val from = i
        while (i < s.length && (peek(0).isLetterOrDigit || peek(0) == '_')) advance(1)
        out += Token('n', s.substring(from, i), start)
      } else if (c.isDigit || (c == '.' && peek(1).isDigit)) {
        val from = i
        while (i < s.length && (peek(0).isLetterOrDigit || peek(0) == '.' || peek(0) == '_' ||
            ((peek(0) == '+' || peek(0) == '-') && "eEpP".indexOf(s.charAt(i - 1)) >= 0))) advance(1)
        out += Token('d', s.substring(from, i), start)
      } else if (c.isWhitespace) {
        advance(1)
      } else {
        val three = if (i + 3 <= s.length) s.substring(i, i + 3) else ""
        val two = if (i + 2 <= s.length) s.substring(i, i + 2) else ""
        val op =
          if (three == "...") three
          else if (Set("//", "<<", ">>", "~=", "==", "<=", ">=", "..", "::")(two)) two
          else c.toString
        advance(op.length)
        out += Token('o', op, start)
      }
    }
    out.toIndexedSeq
  }
}
