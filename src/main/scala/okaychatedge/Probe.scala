package okaychatedge

import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/**
 * okay's PROBE (okay-intent `Probe`): multinomial logistic regression over the normalised embeddings, by plain
 * gradient descent — weights from zero, the rows in order, so a fit is deterministic. Fitted at BUILD time (300 epochs
 * over the authored examples is ~90 million multiply-adds: not a Worker's start); the Worker carries the weights, a
 * matrix of classes × dimensions.
 */
final case class Probe(classes: Vector[String], w: Vector[Float64Array], b: Float64Array):
  /** each class's probability, best first */
  def ranked(v: Float32Array): Vector[(String, Double)] =
    val z = Array.tabulate(classes.length) { c =>
      var s = b(c); var i = 0; val row = w(c)
      while i < v.length do { s += row(i) * v(i); i += 1 }
      s
    }
    val p = Probe.softmax(z)
    classes.indices.map(i => (classes(i), p(i))).sortBy(-_._2).toVector

  def toJson: js.Dynamic = js.Dynamic.literal(classes = js.Array(classes*),
    w = js.Array(w.map(r => js.Array((0 until r.length).map(r(_))*))*), b = js.Array((0 until b.length).map(b(_))*))

object Probe:
  def softmax(z: Array[Double]): Array[Double] =
    val m = z.max
    val e = z.map(x => math.exp(x - m))
    val s = e.sum
    if s == 0.0 then e else e.map(_ / s)

  /** okay's `Probe.train`, its defaults: 300 epochs, rate 0.5 */
  def train(rows: Vector[(Float32Array, String)], epochs: Int = 300, rate: Double = 0.5): Probe =
    val classes = rows.map(_._2).distinct.sorted
    val dim = rows.headOption.map(_._1.length).getOrElse(0)
    val p = Probe(classes, classes.map(_ => new Float64Array(dim)), new Float64Array(classes.length))
    val indexed = rows.map((v, c) => (v, classes.indexOf(c)))
    var epoch = 0
    while epoch < epochs do
      for (u, target) <- indexed do
        val probs = p.ranked(u).toMap
        var c = 0
        while c < classes.length do
          val err = probs(classes(c)) - (if c == target then 1.0 else 0.0)
          val row = p.w(c)
          var i = 0
          while i < dim do { row(i) = row(i) - rate * err * u(i); i += 1 }
          p.b(c) = p.b(c) - rate * err
          c += 1
      epoch += 1
    p

  def fromJson(j: js.Dynamic): Probe =
    val w = j.w.asInstanceOf[js.Array[js.Array[Double]]].toVector.map(r => new Float64Array(r))
    Probe(j.classes.asInstanceOf[js.Array[String]].toVector, w, new Float64Array(j.b.asInstanceOf[js.Array[Double]]))
