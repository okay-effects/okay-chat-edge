package okaychatedge

/**
 * okay-chat's RULES AS NATIVE REGEXES. They are Java's, `(?iU)`: case-insensitive, and `\w`, `\s`, `\d`, `\b` by
 * Unicode. Scala.js's `java.util.regex` gives exactly that, but builds each `\w` into a class of all of Unicode at
 * compile time: 188 rules took 420 ms, more than a Worker may spend starting. Written natively with Unicode PROPERTY
 * classes they still took 209 ms — V8 builds `\p{Alphabetic}` anew for each of the ~2000 classes (0.4 ms each). So the
 * classes are RANGES: the letters of the scripts okay-chat speaks (Latin with its extensions — Polish — and Cyrillic
 * with its supplement — Ukrainian), combining marks, digits, the joiners: 0.02 ms a class.
 *
 * The one difference from Java, by design: a letter of another script (Greek, Hebrew, CJK) is not a word character
 * here. The rules are written for ru, uk, pl and en; `bench/rules.mjs` checks every rule against Java's semantics on
 * the authored corpus. `\b` is a boundary of that class by lookaround; `(?iU)` at the head and the five escapes are all
 * the rules use — any other escape is refused, so a new construct is seen, not mistranslated.
 */
object Rules:
  /** a word character, as the body of a class: Latin (Basic, Latin-1, Extended A and B, Additional), Cyrillic (and
   * its Supplement), combining diacritics, digits, `_`, the joiners */
  private val W = "0-9A-Za-z_\\u00AA\\u00B5\\u00BA\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u024F\\u0300-\\u036F" +
    "\\u0400-\\u052F\\u1E00-\\u1EFF\\u200C\\u200D"
  /** White_Space */
  private val S = "\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"
  private val B = s"(?:(?<=[$W])(?![$W])|(?<![$W])(?=[$W]))"

  /** a Java `(?iU)` rule as a JavaScript regex */
  def compile(java: String): Re =
    val head = "(?iU)"
    require(java.startsWith(head), s"a rule without $head: $java")
    Re(translate(java.drop(head.length)), "iu")

  def translate(src: String): String =
    val out = new StringBuilder
    var inClass = false
    var i = 0
    while i < src.length do
      val c = src.charAt(i)
      if c == '\\' && i + 1 < src.length then
        val e = src.charAt(i + 1)
        out ++= ((e, inClass) match
          case ('w', false) => s"[$W]"
          case ('w', true) => W
          case ('s', false) => s"[$S]"
          case ('s', true) => S
          case ('S', false) => s"[^$S]"
          case ('d', false) => "[0-9]"
          case ('d', true) => "0-9"
          case ('b', false) => B
          case (x, _) if !x.isLetterOrDigit => s"\\$x"
          case (x, _) => throw IllegalArgumentException(s"an escape the translation does not know: \\$x in $src"))
        i += 2
      else
        if c == '[' && !inClass then inClass = true
        else if c == ']' && inClass then inClass = false
        out += c
        i += 1
    out.result()
