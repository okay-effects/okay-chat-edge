package okaychatedge

import scala.collection.mutable

/**
 * okay-chat's STATIC TABLE (okay's `Static`, distilled from paraphrase-multilingual-MiniLM-L12-v2, cut to 256 by PCA):
 * a vector per unit — a word, two or three adjacent words — and a weight. A message's embedding is the weighted mean
 * of its units' vectors, normalised: no inference, a lookup and a sum. The vectors stay half-precision where the file
 * put them; only a message's own units are read.
 */
final class StaticTable(file: Safetensors):
  val dim: Int = file.meta("dim").map(_.toInt).getOrElse(256)
  private val vecs = file.u16("vecs")
  private val weights = file.f64("weights")
  /** each unit's row */
  private val rows: mutable.HashMap[String, Int] =
    val m = mutable.HashMap.empty[String, Int]
    file.strings("labels.offsets", "labels.bytes").zipWithIndex.foreach((l, i) => m(l) = i)
    m

  def size: Int = rows.size

  /** a unit's vector added into `acc`, times `w` */
  private def add(row: Int, w: Double, acc: Array[Float]): Unit =
    val at = row * dim
    var i = 0
    while i < dim do
      acc(i) = acc(i) + (StaticTable.half(vecs(at + i)) * w).toFloat
      i += 1

  /** a message's embedding: its units' weighted mean, normalised — or none, no unit of it known */
  def encode(text: String): Option[Array[Float]] =
    val acc = new Array[Float](dim)
    var known = 0
    for u <- StaticTable.units3(text) do
      rows.get(u) match
        case Some(r) =>
          add(r, weights(r), acc)
          known += 1
        case None =>
    if known == 0 then None else Some(StaticTable.normalised(acc))

object StaticTable:
  /** half precision to single: a table of all 65 536 values, made once */
  private val halves: Array[Float] =
    val t = new Array[Float](65536)
    var h = 0
    while h < 65536 do
      val s = if (h & 0x8000) != 0 then -1.0 else 1.0
      val e = (h >> 10) & 0x1f
      val f = h & 0x3ff
      t(h) = (
        if e == 0 then s * math.pow(2, -14) * (f / 1024.0)
        else if e == 31 then (if f == 0 then s * Double.PositiveInfinity else Double.NaN)
        else s * math.pow(2, e - 15) * (1 + f / 1024.0)
      ).toFloat
      h += 1
    t
  inline def half(h: Int): Float = halves(h)

  private val split = "[^\\p{L}\\p{N}']+".r

  /** okay's `Static.tokens`: lower case, split on what is not a letter, a digit or an apostrophe */
  def tokens(text: String): Vector[String] = split.split(text.toLowerCase).iterator.filter(_.nonEmpty).toVector

  /** okay's `Static.units3`: the words, their adjacent pairs and triples */
  def units3(text: String): Vector[String] =
    val ws = tokens(text)
    ws ++ ws.sliding(2).collect { case Vector(a, b) => s"$a $b" } ++
      ws.sliding(3).collect { case Vector(a, b, c) => s"$a $b $c" }

  def normalised(v: Array[Float]): Array[Float] =
    var s = 0.0
    var i = 0
    while i < v.length do { s += v(i) * v(i); i += 1 }
    val n = math.sqrt(s)
    if n > 0 then
      i = 0
      while i < v.length do { v(i) = (v(i) / n).toFloat; i += 1 }
    v

  def dot(a: Array[Float], b: Array[Float]): Double =
    var s = 0.0
    var i = 0
    while i < a.length do { s += a(i) * b(i); i += 1 }
    s
