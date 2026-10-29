package okaychatedge

import okay.effects.json.{Descent, Json}

import java.util.regex.{Matcher, Pattern}

/** A REGEX, the JVM's own: the same class in the Worker is JavaScript's (its flags `i`, `u` read alike) */
final class Re private (p: Pattern):
  def test(s: String): Boolean = p.matcher(s).find()
  /** group `n` of the first match */
  def group(s: String, n: Int = 1): Option[String] =
    val m = p.matcher(s)
    if m.find() && m.groupCount >= n then Option(m.group(n)) else None
  def replaceAll(s: String, by: String): String = p.matcher(s).replaceAll(Matcher.quoteReplacement(by))
  def split(s: String): Vector[String] = p.split(s, -1).toVector

object Re:
  def apply(pattern: String, flags: String = ""): Re =
    val bits = (if flags.contains('i') then Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE else 0) |
      (if flags.contains('u') then Pattern.UNICODE_CASE else 0)
    new Re(Pattern.compile(pattern, bits))
  /** a pattern in Java's own syntax, its flags inline (okay-chat's rules: `(?iU)`) */
  def java(pattern: String): Re = new Re(Pattern.compile(pattern))

/** A JSON VALUE, read by okay-simple's parser (the same class in the Worker reads JavaScript's `JSON.parse`) */
final class J private (v: Option[Json]):
  def isDefined: Boolean = v.exists(_ != Json.Null)
  def get(k: String): Option[J] = v.flatMap(_.field(k)).filter(_ != Json.Null).map(x => J(Some(x)))
  def apply(k: String): J = get(k).getOrElse(J.none)
  def str: Option[String] = v.collect { case Json.Str(s) => s }
  def num: Option[Double] = v.collect { case Json.Num(n) => n.toDouble }
  def bool: Option[Boolean] = v.collect { case Json.Bool(b) => b }
  def arr: Vector[J] = v.collect { case Json.Arr(xs) => xs.map(x => J(Some(x))) }.getOrElse(Vector.empty)
  def fields: Vector[(String, J)] = v.collect { case Json.Obj(fs) => fs.map((k, x) => k -> J(Some(x))) }
    .getOrElse(Vector.empty)

object J:
  val none: J = J(None)
  /** JSON text read; not JSON, an exception */
  def parse(s: String): J =
    J(Some(Descent.parse(s).fold(e => throw IllegalArgumentException(s"not JSON: ${e.message}"), identity)))

/** what text and time ask of the platform */
object Text:
  /** accents folded: «Wrocław» → «wroclaw» (NFD, its combining marks dropped), lower case */
  def fold(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).toLowerCase
      .filterNot(c => Character.getType(c) == Character.NON_SPACING_MARK)
  /** an ISO 8601 time, in milliseconds */
  def isoMillis(s: String): Option[Long] =
    try Some(java.time.OffsetDateTime.parse(s).toInstant.toEpochMilli)
    catch case _: Exception =>
      try Some(java.time.Instant.parse(s).toEpochMilli) catch case _: Exception => None
