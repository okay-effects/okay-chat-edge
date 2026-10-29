package okaychatedge

import scala.scalajs.js
import scala.scalajs.js.JSStringOps.*

/**
 * A REGEX, JavaScript's own (the Worker): V8 compiles it natively — Scala.js's `java.util.regex` would take hundreds
 * of milliseconds to build okay-chat's rules (Rules). The same class on the JVM is `java.util.regex`'s.
 */
final class Re private (native: js.RegExp, global: js.RegExp):
  def test(s: String): Boolean =
    native.lastIndex = 0
    native.test(s)
  /** group `n` of the first match */
  def group(s: String, n: Int = 1): Option[String] =
    native.lastIndex = 0
    val m = native.exec(s)
    if m == null || m.length <= n then None else m(n).toOption
  def replaceAll(s: String, by: String): String = s.jsReplace(global, by)
  def split(s: String): Vector[String] = s.jsSplit(native).toVector

object Re:
  /** a pattern in JavaScript's syntax and flags (`i`, `u`), which the JVM's reads alike */
  def apply(pattern: String, flags: String = ""): Re =
    val f = flags.filterNot(_ == 'g')
    new Re(new js.RegExp(pattern, f), new js.RegExp(pattern, f + "g"))

/**
 * A JSON VALUE, read by the platform's own parser: here JavaScript's `JSON.parse`, the values not copied — a board of
 * vacancies (1.4 MB) is read in about a millisecond and a half, where a parser written in Scala would take the whole
 * of a request's 10 ms. The same class on the JVM reads okay-simple's JSON.
 */
final class J private (v: js.Any):
  private def isObject: Boolean = js.typeOf(v) == "object" && v != null && !js.Array.isArray(v)
  def isDefined: Boolean = !js.isUndefined(v) && v != null
  def get(k: String): Option[J] =
    if !isObject then None
    else
      val x = v.asInstanceOf[js.Dynamic].selectDynamic(k)
      Option.when(!js.isUndefined(x) && x != null)(J(x))
  def apply(k: String): J = get(k).getOrElse(J.none)
  def str: Option[String] = (v: Any) match
    case s: String => Some(s)
    case _ => None
  def num: Option[Double] = (v: Any) match
    case d: Double => Some(d)
    case _ => None
  def bool: Option[Boolean] = (v: Any) match
    case b: Boolean => Some(b)
    case _ => None
  def arr: Vector[J] =
    if js.Array.isArray(v) then v.asInstanceOf[js.Array[js.Any]].toVector.map(J(_)) else Vector.empty
  def fields: Vector[(String, J)] =
    if !isObject then Vector.empty
    else js.Object.keys(v.asInstanceOf[js.Object]).toVector.map(k => k -> J(v.asInstanceOf[js.Dynamic].selectDynamic(k)))

object J:
  val none: J = J(js.undefined)
  /** JSON text read; not JSON, an exception */
  def parse(s: String): J = J(js.JSON.parse(s))

/** what text and time ask of the platform */
object Text:
  /** accents folded: «Wrocław» → «wroclaw» (NFD, its combining marks dropped), lower case */
  def fold(s: String): String =
    s.asInstanceOf[js.Dynamic].normalize("NFD").asInstanceOf[String].toLowerCase
      .filterNot(c => Character.getType(c) == Character.NON_SPACING_MARK)
  /** an ISO 8601 time, in milliseconds */
  def isoMillis(s: String): Option[Long] =
    val t = js.Date.parse(s)
    Option.when(!t.isNaN)(t.toLong)
